package com.transport.backend.service.occupancy;

import com.transport.backend.dto.OccupancyDto;
import com.transport.backend.entity.Bus;
import com.transport.backend.entity.BusOccupancy;
import com.transport.backend.repository.BusOccupancyRepository;
import com.transport.backend.repository.BusRepository;
import com.transport.backend.repository.SensorRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * SIMULATED passenger counts for the demo, for buses that have NO passenger-counting sensor.
 *
 * Every run (default 30 s) the onboard count of each such active bus with a route moves towards a time-of-day demand
 * level (morning and evening peaks, quieter weekends) with some noise, and always stays between 0 and the bus capacity.
 * The value is stored with method and data mode SIMULATED, without a confidence and without seat data, so the UI labels
 * it "Simulated" and seats stay "unknown". No observation rows are written (the observation log is for real sensors and
 * manual counts), so the database does not grow. Buses with a registered sensor, or with camera / manual data, are never
 * touched. Disable with SIMULATE_PASSENGERS=false.
 */
@Service
public class PassengerSimulationService {

    public static final String MODE_SIMULATED = "SIMULATED";

    /** Fraction of capacity a typical demo bus carries at each hour of a weekday (synthetic profile). */
    private static final double[] WEEKDAY_DEMAND = {
            0.05, 0.03, 0.02, 0.02, 0.05, 0.15, 0.35, 0.60, 0.85, 0.75, 0.50, 0.45,
            0.50, 0.45, 0.45, 0.50, 0.65, 0.85, 0.92, 0.70, 0.50, 0.35, 0.20, 0.10};
    private static final double WEEKEND_FACTOR = 0.7;

    private final BusRepository busRepository;
    private final SensorRepository sensorRepository;
    private final BusOccupancyRepository occupancyRepository;
    private final OccupancyService occupancyService;
    private final boolean enabled;
    private final Random random;

    public PassengerSimulationService(BusRepository busRepository, SensorRepository sensorRepository,
                                      BusOccupancyRepository occupancyRepository, OccupancyService occupancyService,
                                      @Value("${app.simulation.passengers.enabled:true}") boolean enabled) {
        this.busRepository = busRepository;
        this.sensorRepository = sensorRepository;
        this.occupancyRepository = occupancyRepository;
        this.occupancyService = occupancyService;
        this.enabled = enabled;
        this.random = new Random();
    }

    public boolean isEnabled() {
        return enabled;
    }

    /** Demand level (fraction of capacity) for a route at a time; the route id varies it a little per route. */
    static double demand(LocalDateTime at, Long routeId) {
        double level = WEEKDAY_DEMAND[at.getHour()];
        if (at.getDayOfWeek() == DayOfWeek.SATURDAY || at.getDayOfWeek() == DayOfWeek.SUNDAY) {
            level *= WEEKEND_FACTOR;
        }
        double routeFactor = 0.8 + (routeId == null ? 0 : routeId % 5) * 0.1;   // 0.8 .. 1.2
        return Math.min(1.05, level * routeFactor);
    }

    /** Next onboard value: a step towards the target with noise, clamped to [0, capacity]. */
    static int nextOnboard(Integer current, int capacity, double targetFraction, double noise) {
        double target = targetFraction * capacity;
        double from = current == null ? target : current;
        long next = Math.round(from + (target - from) * 0.35 + noise);
        return (int) Math.max(0, Math.min(capacity, next));
    }

    /** Updates every eligible bus; returns their new occupancy for broadcasting. */
    @Transactional
    public List<OccupancyDto> tick() {
        List<OccupancyDto> updates = new ArrayList<>();
        if (!enabled) {
            return updates;
        }
        LocalDateTime now = LocalDateTime.now();
        for (Bus bus : busRepository.findByStatus(Bus.STATUS_ACTIVE)) {
            if (bus.getRoute() == null || bus.getCapacity() == null || bus.getCapacity() <= 0
                    || sensorRepository.countByBusId(bus.getId()) > 0) {
                continue;
            }
            BusOccupancy state = occupancyRepository.findByBusIdForUpdate(bus.getId())
                    .orElseGet(() -> BusOccupancy.builder().bus(bus).build());
            if (state.getDataMode() != null && !MODE_SIMULATED.equals(state.getDataMode())) {
                continue;   // real camera or manual data: never overwritten by the simulation
            }
            int onboard = nextOnboard(state.getEstimatedOnboard(), bus.getCapacity(),
                    demand(now, bus.getRoute().getId()), random.nextGaussian() * 2);
            state.setEstimatedOnboard(onboard);
            state.setOnboardMethod(MODE_SIMULATED);
            state.setDataMode(MODE_SIMULATED);
            state.setConfidence(null);
            state.setUpdatedAt(now);
            occupancyRepository.save(state);
            updates.add(occupancyService.buildDto(bus, now));
        }
        return updates;
    }
}
