package com.transport.backend.service;

import com.transport.backend.dto.LocationDto;
import com.transport.backend.entity.Bus;
import com.transport.backend.entity.Location;
import com.transport.backend.entity.Route;
import com.transport.backend.repository.BusRepository;
import com.transport.backend.repository.LocationRepository;
import com.transport.backend.service.geo.RoutePath;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * SIMULATED GPS (SRS algorithm 3). No real GPS hardware is used.
 *
 * Every tick (default 5 s) each ACTIVE bus with a route of at least two stops moves along the
 * straight lines between its stops at roughly the configured average speed, pauses briefly at
 * stops, and turns around at either end. The new position is saved to the bus row and to the
 * locations history table, and returned so the scheduler can broadcast it over WebSocket.
 *
 * Buses that received a real GPS fix through POST /api/location/update in the last minute are
 * not moved by the simulator, so real devices and simulation can coexist.
 */
@Service
public class BusSimulationService {

    private static final double MAX_SNAP_DISTANCE_M = 2000;
    private static final int DWELL_TICKS_AT_STOP = 2;
    private static final Duration GPS_OVERRIDE_WINDOW = Duration.ofSeconds(60);

    private final BusRepository busRepository;
    private final LocationRepository locationRepository;
    private final double averageSpeedKmph;
    private final long intervalMs;
    private final Random random = new Random();

    /** In-memory movement state per bus. Rebuilt automatically after a restart. */
    private final Map<Long, BusState> states = new ConcurrentHashMap<>();
    private final Map<Long, LocalDateTime> lastGpsFix = new ConcurrentHashMap<>();

    static final class BusState {
        Long routeId;
        double distance;      // metres along the route path
        int direction;        // +1 towards destination, -1 towards source
        double baseSpeedKmph; // each bus has its own typical speed
        double currentSpeedKmph;
        int dwellTicks;
    }

    public BusSimulationService(BusRepository busRepository,
                                LocationRepository locationRepository,
                                @Value("${app.simulation.average-speed-kmph:28}") double averageSpeedKmph,
                                @Value("${app.simulation.interval-ms:5000}") long intervalMs) {
        this.busRepository = busRepository;
        this.locationRepository = locationRepository;
        this.averageSpeedKmph = averageSpeedKmph;
        this.intervalMs = intervalMs;
    }

    /** Moves all eligible buses one step. Returns the new positions (already saved). */
    @Transactional
    public List<LocationDto> tick() {
        List<Bus> active = busRepository.findByStatus(Bus.STATUS_ACTIVE);
        Map<Long, List<Bus>> byRoute = new HashMap<>();
        for (Bus bus : active) {
            if (bus.getRoute() != null && RoutePath.isUsable(bus.getRoute().getStops())) {
                byRoute.computeIfAbsent(bus.getRoute().getId(), k -> new ArrayList<>()).add(bus);
            }
        }

        LocalDateTime now = LocalDateTime.now();
        List<Bus> movedBuses = new ArrayList<>();
        List<Location> history = new ArrayList<>();
        List<LocationDto> updates = new ArrayList<>();

        for (List<Bus> routeBuses : byRoute.values()) {
            routeBuses.sort(Comparator.comparing(Bus::getId));
            Route route = routeBuses.get(0).getRoute();
            RoutePath path = RoutePath.of(route);

            for (int i = 0; i < routeBuses.size(); i++) {
                Bus bus = routeBuses.get(i);
                LocalDateTime gps = lastGpsFix.get(bus.getId());
                if (gps != null && gps.isAfter(now.minus(GPS_OVERRIDE_WINDOW))) {
                    continue; // a real device is reporting this bus
                }
                BusState state = states.get(bus.getId());
                if (state == null || !route.getId().equals(state.routeId)) {
                    state = initialState(bus, route, path, i, routeBuses.size());
                    states.put(bus.getId(), state);
                }
                double[] before = path.pointAt(state.distance);
                advance(state, path);
                double[] point = path.pointAt(state.distance);

                bus.setCurrentLatitude(point[0]);
                bus.setCurrentLongitude(point[1]);
                bus.setLastUpdated(now);
                movedBuses.add(bus);
                history.add(Location.builder().bus(bus).latitude(point[0]).longitude(point[1])
                        .timestamp(now).source(Location.SOURCE_SIMULATED).build());

                double heading = (before[0] == point[0] && before[1] == point[1])
                        ? Double.NaN : RoutePath.bearing(before[0], before[1], point[0], point[1]);
                updates.add(LocationDto.builder()
                        .busId(bus.getId())
                        .busNumber(bus.getBusNumber())
                        .routeId(route.getId())
                        .latitude(point[0])
                        .longitude(point[1])
                        .timestamp(now)
                        .status(bus.getStatus())
                        .source(Location.SOURCE_SIMULATED)
                        .speedKmph(Math.round(state.currentSpeedKmph * 10) / 10.0)
                        .heading(Double.isNaN(heading) ? null : Math.round(heading * 10) / 10.0)
                        .nextStop(path.stop(path.nextStopIndex(state.distance, state.direction)).getName())
                        .direction(path.stop(state.direction > 0 ? path.stopCount() - 1 : 0).getName())
                        .build());
            }
        }

        busRepository.saveAll(movedBuses);
        locationRepository.saveAll(history);
        // Forget buses that are no longer active so they restart cleanly if re-activated.
        states.keySet().removeIf(id -> active.stream().noneMatch(b -> b.getId().equals(id)));
        return updates;
    }

    private BusState initialState(Bus bus, Route route, RoutePath path, int index, int busesOnRoute) {
        BusState state = new BusState();
        state.routeId = route.getId();
        state.direction = index % 2 == 0 ? 1 : -1;
        state.baseSpeedKmph = averageSpeedKmph * (0.85 + random.nextDouble() * 0.3);
        state.currentSpeedKmph = state.baseSpeedKmph;
        if (bus.getCurrentLatitude() != null && bus.getCurrentLongitude() != null) {
            double[] projection = path.project(bus.getCurrentLatitude(), bus.getCurrentLongitude());
            if (projection[1] <= MAX_SNAP_DISTANCE_M) {
                state.distance = projection[0];
                return state;
            }
        }
        // Spread buses of the same route evenly along it.
        state.distance = path.lengthMeters() * (index + 0.5) / busesOnRoute;
        return state;
    }

    private void advance(BusState state, RoutePath path) {
        if (state.dwellTicks > 0) {
            state.dwellTicks--;
            state.currentSpeedKmph = 0;
            return;
        }
        state.currentSpeedKmph = state.baseSpeedKmph * (0.8 + random.nextDouble() * 0.4);
        double step = state.currentSpeedKmph / 3.6 * (intervalMs / 1000.0);
        int nextStop = path.nextStopIndex(state.distance, state.direction);
        double stopDistance = path.distanceAtStop(nextStop);
        double target = state.distance + state.direction * step;

        boolean reachesStop = state.direction > 0 ? target >= stopDistance : target <= stopDistance;
        if (reachesStop) {
            state.distance = stopDistance;
            state.dwellTicks = DWELL_TICKS_AT_STOP;
            if (nextStop == 0 || nextStop == path.stopCount() - 1) {
                state.direction = -state.direction; // turn around at the terminus
            }
        } else {
            state.distance = target;
        }
    }

    /** Movement details the simulator currently knows for a bus (shown before the next WebSocket message). */
    public record LiveInfo(String nextStop, String direction, Double speedKmph) {
    }

    public Optional<LiveInfo> liveInfo(Bus bus) {
        BusState state = states.get(bus.getId());
        if (state == null || bus.getRoute() == null || !RoutePath.isUsable(bus.getRoute().getStops())
                || !bus.getRoute().getId().equals(state.routeId)) {
            return Optional.empty();
        }
        RoutePath path = RoutePath.of(bus.getRoute());
        return Optional.of(new LiveInfo(
                path.stop(path.nextStopIndex(state.distance, state.direction)).getName(),
                path.stop(state.direction > 0 ? path.stopCount() - 1 : 0).getName(),
                Math.round(state.currentSpeedKmph * 10) / 10.0));
    }

    /** Called when a real GPS fix arrives, so the simulator leaves this bus alone for a while. */
    public void recordGpsFix(Long busId) {
        lastGpsFix.put(busId, LocalDateTime.now());
        states.remove(busId);
    }

    public void forget(Long busId) {
        states.remove(busId);
        lastGpsFix.remove(busId);
    }

    /**
     * Estimated minutes until a bus reaches the given stop, following its current direction
     * (and turning at the terminus if it is moving away). Null if it cannot be estimated.
     */
    public Integer estimateMinutesToStop(Bus bus, RoutePath path, int stopIndex) {
        BusState state = states.get(bus.getId());
        double position;
        int direction;
        if (state != null) {
            position = state.distance;
            direction = state.direction;
        } else if (bus.getCurrentLatitude() != null && bus.getCurrentLongitude() != null) {
            double[] projection = path.project(bus.getCurrentLatitude(), bus.getCurrentLongitude());
            if (projection[1] > MAX_SNAP_DISTANCE_M) {
                return null;
            }
            position = projection[0];
            direction = 1;
        } else {
            return null;
        }
        double stop = path.distanceAtStop(stopIndex);
        double metres;
        if ((direction > 0 && stop >= position) || (direction < 0 && stop <= position)) {
            metres = Math.abs(stop - position);
        } else if (direction > 0) {
            metres = (path.lengthMeters() - position) + (path.lengthMeters() - stop);
        } else {
            metres = position + stop;
        }
        double minutes = metres / (averageSpeedKmph * 1000 / 60);
        return (int) Math.ceil(minutes);
    }

    public double getAverageSpeedKmph() {
        return averageSpeedKmph;
    }
}
