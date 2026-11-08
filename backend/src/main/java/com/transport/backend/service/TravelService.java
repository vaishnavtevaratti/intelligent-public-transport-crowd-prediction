package com.transport.backend.service;

import com.transport.backend.dto.CrowdPredictionDto;
import com.transport.backend.dto.RouteDto;
import com.transport.backend.dto.TravelOptionDto;
import com.transport.backend.entity.Bus;
import com.transport.backend.entity.CrowdLevel;
import com.transport.backend.entity.CrowdPrediction;
import com.transport.backend.entity.Route;
import com.transport.backend.entity.RouteStop;
import com.transport.backend.exception.BadRequestException;
import com.transport.backend.exception.ServiceUnavailableException;
import com.transport.backend.repository.BusRepository;
import com.transport.backend.repository.RouteRepository;
import com.transport.backend.service.geo.RoutePath;
import com.transport.backend.service.ml.CrowdModelClient;
import com.transport.backend.service.routing.GoogleRoutesClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;

/**
 * Travel information (SRS: "provide travel-related information to users based on crowd data").
 * Finds routes that connect two stops and combines route data, live bus positions with ETAs,
 * the current ML crowd prediction and the best hours to travel today.
 */
@Service
public class TravelService {

    private final RouteRepository routeRepository;
    private final BusRepository busRepository;
    private final RouteService routeService;
    private final CrowdPredictionService crowdPredictionService;
    private final BusSimulationService simulationService;
    private final CrowdModelClient modelClient;
    private final GoogleRoutesClient routesClient;

    public TravelService(RouteRepository routeRepository, BusRepository busRepository, RouteService routeService,
                         CrowdPredictionService crowdPredictionService, BusSimulationService simulationService,
                         CrowdModelClient modelClient, GoogleRoutesClient routesClient) {
        this.routesClient = routesClient;
        this.routeRepository = routeRepository;
        this.busRepository = busRepository;
        this.routeService = routeService;
        this.crowdPredictionService = crowdPredictionService;
        this.simulationService = simulationService;
        this.modelClient = modelClient;
    }

    /** All stop names users can choose from (route stops, plus endpoints of routes without stops). */
    @Transactional(readOnly = true)
    public List<String> listStops() {
        TreeSet<String> names = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        for (Route route : routeRepository.findAllWithStops()) {
            if (route.getStops().isEmpty()) {
                names.add(route.getSource());
                names.add(route.getDestination());
            } else {
                route.getStops().forEach(s -> names.add(s.getName()));
            }
        }
        return new ArrayList<>(names);
    }

    @Transactional(readOnly = true)
    public List<TravelOptionDto> search(String from, String to) {
        if (from == null || to == null || from.isBlank() || to.isBlank()) {
            throw new BadRequestException("Both 'from' and 'to' stops are required");
        }
        if (from.trim().equalsIgnoreCase(to.trim())) {
            throw new BadRequestException("Choose two different stops");
        }

        Map<Long, CrowdPrediction> latest = crowdPredictionService.latestPerBus();
        boolean modelUp = modelClient.isAvailable();
        List<TravelOptionDto> options = new ArrayList<>();

        for (Route route : routeRepository.findAllWithStops()) {
            List<String> stopNames = route.getStops().isEmpty()
                    ? List.of(route.getSource(), route.getDestination())
                    : route.getStops().stream().map(RouteStop::getName).toList();
            int fromIndex = indexOfIgnoreCase(stopNames, from.trim());
            int toIndex = indexOfIgnoreCase(stopNames, to.trim());
            if (fromIndex < 0 || toIndex < 0) {
                continue;
            }
            options.add(buildOption(route, stopNames, fromIndex, toIndex, latest, modelUp));
        }

        options.sort(Comparator.comparing((TravelOptionDto o) -> o.getDistanceKm() == null ? Double.MAX_VALUE : o.getDistanceKm()));
        return options;
    }

    private TravelOptionDto buildOption(Route route, List<String> stopNames, int fromIndex, int toIndex,
                                        Map<Long, CrowdPrediction> latest, boolean modelUp) {
        boolean hasPath = RoutePath.isUsable(route.getStops());
        RoutePath path = hasPath ? RoutePath.of(route) : null;
        List<Bus> buses = busRepository.findByRouteId(route.getId());

        Double distanceKm = null;
        Integer minutes = null;
        String routingSource = null;
        if (path != null) {
            int intermediateStops = Math.abs(toIndex - fromIndex) - 1;
            // Road distance/time from the Google Routes API when configured (stops in travel order) ...
            List<GoogleRoutesClient.LatLng> points = new ArrayList<>();
            int step = toIndex > fromIndex ? 1 : -1;
            for (int i = fromIndex; i != toIndex + step; i += step) {
                points.add(new GoogleRoutesClient.LatLng(route.getStops().get(i).getLatitude(), route.getStops().get(i).getLongitude()));
            }
            Optional<GoogleRoutesClient.RoadEstimate> road = routesClient.estimate(points);
            if (road.isPresent()) {
                distanceKm = road.get().distanceKm();
                minutes = (int) Math.ceil(road.get().durationMinutes() + intermediateStops * 0.5); // + ~30 s dwell per stop
                routingSource = "GOOGLE_ROUTES";
            } else {
                // ... otherwise along the stored road geometry (OpenStreetMap roads) or, without it, straight lines
                // between stops, at the average bus speed + ~30 s dwell per intermediate stop
                double metres = Math.abs(path.distanceAtStop(toIndex) - path.distanceAtStop(fromIndex));
                distanceKm = Math.round(metres / 100.0) / 10.0;
                minutes = (int) Math.ceil(metres / (simulationService.getAverageSpeedKmph() * 1000 / 60) + intermediateStops * 0.5);
                routingSource = path.followsRoads() ? "ROAD_GEOMETRY_ESTIMATE" : "STRAIGHT_LINE_ESTIMATE";
            }
        }

        List<TravelOptionDto.TravelBus> travelBuses = new ArrayList<>();
        for (Bus bus : buses) {
            CrowdPrediction p = latest.get(bus.getId());
            Integer eta = path != null && Bus.STATUS_ACTIVE.equals(bus.getStatus())
                    ? simulationService.estimateMinutesToStop(bus, path, fromIndex) : null;
            travelBuses.add(TravelOptionDto.TravelBus.builder()
                    .busId(bus.getId())
                    .busNumber(bus.getBusNumber())
                    .status(bus.getStatus())
                    .capacity(bus.getCapacity())
                    .latitude(bus.getCurrentLatitude())
                    .longitude(bus.getCurrentLongitude())
                    .lastUpdated(bus.getLastUpdated())
                    .etaMinutes(eta)
                    .latestCrowdLevel(p != null ? p.getCrowdLevel() : null)
                    .build());
        }
        travelBuses.sort(Comparator.comparing((TravelOptionDto.TravelBus b) -> b.getEtaMinutes() == null ? Integer.MAX_VALUE : b.getEtaMinutes()));

        CrowdPredictionDto crowdNow = null;
        List<Integer> bestHours = List.of();
        String note = path == null ? "This route has no stop coordinates yet, so map, distance and arrival times are unavailable." : null;
        if (modelUp) {
            try {
                Bus reference = buses.stream().filter(b -> Bus.STATUS_ACTIVE.equals(b.getStatus())).findFirst()
                        .orElse(buses.isEmpty() ? null : buses.get(0));
                int capacity = reference != null && reference.getCapacity() != null ? reference.getCapacity() : CrowdPredictionService.DEFAULT_CAPACITY;
                CrowdModelClient.ModelOutput out = modelClient.predictCached(List.of(   // read-only view: not stored
                        crowdPredictionService.buildInput(route, capacity, LocalDateTime.now()))).get(0);
                if (out.extrapolated()) {
                    throw new com.transport.backend.exception.BadRequestException(
                            "Crowd prediction is not available for this route (outside the training data of the model).");
                }
                crowdNow = CrowdPredictionDto.builder()
                        .routeId(route.getId())
                        .routeSource(route.getSource())
                        .routeDestination(route.getDestination())
                        .crowdLevel(CrowdLevel.valueOf(out.crowdLevel()))
                        .confidence(out.confidence())
                        .probabilities(out.probabilities())
                        .modelVersion(out.modelVersion())
                        .predictedFor(LocalDateTime.now())
                        .busCapacity(capacity)
                        .build();
                bestHours = crowdPredictionService.bestHoursToday(route.getId(), LocalDateTime.now().getHour());
            } catch (ServiceUnavailableException ex) {
                note = appendNote(note, "Crowd prediction service is unavailable right now.");
            } catch (com.transport.backend.exception.BadRequestException ex) {
                note = appendNote(note, ex.getMessage());
            }
        } else {
            note = appendNote(note, "Crowd prediction service is unavailable right now.");
        }

        RouteDto routeDto = routeService.convertToDto(route, (long) buses.size());
        return TravelOptionDto.builder()
                .route(routeDto)
                .fromStop(stopNames.get(fromIndex))
                .toStop(stopNames.get(toIndex))
                .forward(toIndex > fromIndex)
                .stopsBetween(Math.abs(toIndex - fromIndex) - 1)
                .distanceKm(distanceKm)
                .estimatedMinutes(minutes)
                .routingSource(routingSource)
                .crowdNow(crowdNow)
                .bestHoursToday(bestHours)
                .buses(travelBuses)
                .note(note)
                .build();
    }

    private static String appendNote(String note, String extra) {
        return note == null ? extra : note + " " + extra;
    }

    private static int indexOfIgnoreCase(List<String> names, String value) {
        for (int i = 0; i < names.size(); i++) {
            if (names.get(i).equalsIgnoreCase(value)) {
                return i;
            }
        }
        return -1;
    }
}
