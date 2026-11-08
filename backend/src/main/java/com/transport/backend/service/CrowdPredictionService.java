package com.transport.backend.service;

import com.transport.backend.dto.CrowdPredictionDto;
import com.transport.backend.dto.HourlyForecastDto;
import com.transport.backend.dto.PageResponse;
import com.transport.backend.dto.PredictionRequest;
import com.transport.backend.entity.Bus;
import com.transport.backend.entity.CrowdLevel;
import com.transport.backend.entity.CrowdPrediction;
import com.transport.backend.entity.Route;
import com.transport.backend.exception.BadRequestException;
import com.transport.backend.exception.ResourceNotFoundException;
import com.transport.backend.repository.BusRepository;
import com.transport.backend.repository.CrowdPredictionRepository;
import com.transport.backend.repository.RouteRepository;
import com.transport.backend.service.geo.RoutePath;
import com.transport.backend.service.ml.CrowdModelClient;
import com.transport.backend.service.ml.CrowdModelClient.ModelInput;
import com.transport.backend.service.ml.CrowdModelClient.ModelOutput;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Crowd prediction (SRS algorithm 5) and travel-time suggestion (SRS algorithm 6).
 * Every prediction comes from the trained ML model via {@link CrowdModelClient};
 * if the model service is down the request fails with HTTP 503 instead of returning a guess.
 */
@Service
public class CrowdPredictionService {

    static final int DEFAULT_CAPACITY = 50;

    private final CrowdPredictionRepository crowdPredictionRepository;
    private final BusRepository busRepository;
    private final RouteRepository routeRepository;
    private final CrowdModelClient modelClient;

    @Autowired
    public CrowdPredictionService(CrowdPredictionRepository crowdPredictionRepository,
                                  BusRepository busRepository,
                                  RouteRepository routeRepository,
                                  CrowdModelClient modelClient) {
        this.crowdPredictionRepository = crowdPredictionRepository;
        this.busRepository = busRepository;
        this.routeRepository = routeRepository;
        this.modelClient = modelClient;
    }

    @Transactional(readOnly = true)
    public PageResponse<CrowdPredictionDto> getHistory(Long routeId, Long busId, CrowdLevel level, String source,
                                                       int page, int size) {
        int safeSize = Math.min(Math.max(size, 1), 100);
        PageRequest pageable = PageRequest.of(Math.max(page, 0), safeSize, Sort.by(Sort.Direction.DESC, "timestamp"));
        return PageResponse.of(crowdPredictionRepository.search(routeId, busId, level, source, pageable), this::convertToDto);
    }

    /** Predicts the crowd level of one bus at the requested travel time and stores the result. */
    @Transactional
    public CrowdPredictionDto predictForBus(PredictionRequest request) {
        Bus bus = busRepository.findById(request.getBusId())
                .orElseThrow(() -> new ResourceNotFoundException("Bus", "id", request.getBusId()));
        if (bus.getRoute() == null) {
            throw new BadRequestException("Bus " + bus.getBusNumber() + " is not assigned to a route, so its crowd level cannot be predicted");
        }
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime travelTime = request.getTravelTime() != null ? request.getTravelTime() : now;
        if (travelTime.isBefore(now.minusDays(1)) || travelTime.isAfter(now.plusDays(30))) {
            throw new BadRequestException("Travel time must be between yesterday and 30 days from now");
        }

        ModelOutput output = modelClient.predictOne(buildInput(bus.getRoute(), capacityOf(bus), travelTime));
        if (output.extrapolated()) {
            throw new BadRequestException("The crowd model was not trained for this bus/route (" + String.join(", ", output.outOfTrainingRange())
                    + " outside the training data: capacity 30-70, route 3-30 km, 3-25 stops), so no prediction is made");
        }
        CrowdPrediction saved = crowdPredictionRepository.save(toEntity(bus, output, now, travelTime, CrowdPrediction.SOURCE_ON_DEMAND));
        return convertToDto(saved);
    }

    /** Scheduled job: one prediction (for "now") per active bus that has a route. */
    @Transactional
    public List<CrowdPredictionDto> predictForActiveBuses() {
        List<Bus> buses = busRepository.findByStatus(Bus.STATUS_ACTIVE).stream()
                .filter(b -> b.getRoute() != null)
                .toList();
        if (buses.isEmpty()) {
            return List.of();
        }
        LocalDateTime now = LocalDateTime.now();
        List<ModelOutput> outputs = modelClient.predict(buses.stream()
                .map(b -> buildInput(b.getRoute(), capacityOf(b), now)).toList());

        List<CrowdPrediction> entities = new ArrayList<>();
        for (int i = 0; i < buses.size(); i++) {
            if (!outputs.get(i).extrapolated()) { // never store a prediction the model was not trained for
                entities.add(toEntity(buses.get(i), outputs.get(i), now, now, CrowdPrediction.SOURCE_SCHEDULED));
            }
        }
        return crowdPredictionRepository.saveAll(entities).stream().map(this::convertToDto).toList();
    }

    /**
     * Forecast for every hour of a day on a route plus the best hours to travel within [fromHour, toHour].
     * Predictions here are not stored; they are computed on request.
     */
    @Transactional(readOnly = true)
    public HourlyForecastDto forecastForRoute(Long routeId, LocalDate date, int fromHour, int toHour) {
        if (fromHour < 0 || toHour > 23 || fromHour > toHour) {
            throw new BadRequestException("Hour window must satisfy 0 <= fromHour <= toHour <= 23");
        }
        Route route = routeRepository.findByIdWithStops(routeId)
                .orElseThrow(() -> new ResourceNotFoundException("Route", "id", routeId));
        LocalDate day = date != null ? date : LocalDate.now();
        int capacity = representativeCapacity(routeId);

        List<ModelInput> inputs = new ArrayList<>();
        for (int hour = 0; hour < 24; hour++) {
            inputs.add(buildInput(route, capacity, day.atTime(hour, 0)));
        }
        List<ModelOutput> outputs = modelClient.predictCached(inputs);   // read-only view: not stored
        if (outputs.get(0).extrapolated()) {
            throw new BadRequestException("The crowd model was not trained for this route (" + String.join(", ", outputs.get(0).outOfTrainingRange())
                    + " outside the training data), so no forecast is made");
        }

        List<HourlyForecastDto.HourForecast> hours = new ArrayList<>();
        for (int hour = 0; hour < 24; hour++) {
            ModelOutput out = outputs.get(hour);
            hours.add(HourlyForecastDto.HourForecast.builder()
                    .hour(hour)
                    .crowdLevel(out.crowdLevel())
                    .confidence(out.confidence())
                    .probabilities(out.probabilities())
                    .crowdScore(crowdScore(out.probabilities()))
                    .build());
        }

        List<Integer> best = hours.stream()
                .filter(h -> h.getHour() >= fromHour && h.getHour() <= toHour)
                .sorted(Comparator.comparingDouble(HourlyForecastDto.HourForecast::getCrowdScore)
                        .thenComparingInt(HourlyForecastDto.HourForecast::getHour))
                .limit(3)
                .map(HourlyForecastDto.HourForecast::getHour)
                .toList();

        return HourlyForecastDto.builder()
                .routeId(route.getId())
                .routeLabel(route.getSource() + " → " + route.getDestination())
                .date(day)
                .busCapacityUsed(capacity)
                .modelVersion(outputs.get(0).modelVersion())
                .hours(hours)
                .bestHours(best)
                .history(historyByHour(routeId))
                .build();
    }

    /** Best hours today for a route, or an empty list when the model is unavailable. */
    public List<Integer> bestHoursToday(Long routeId, int fromHour) {
        int from = Math.min(Math.max(fromHour, 5), 22);
        return forecastForRoute(routeId, LocalDate.now(), from, 22).getBestHours();
    }

    @Transactional(readOnly = true)
    public Optional<CrowdPredictionDto> latestForBus(Long busId) {
        return crowdPredictionRepository.findTopByBusIdOrderByTimestampDesc(busId).map(this::convertToDto);
    }

    /** HIGH crowd predictions from the last hour, newest first, at most one per bus. */
    @Transactional(readOnly = true)
    public List<CrowdPredictionDto> recentHighCrowdAlerts() {
        List<CrowdPrediction> high = crowdPredictionRepository.findByCrowdLevelAndTimestampAfterOrderByTimestampDesc(
                CrowdLevel.HIGH, LocalDateTime.now().minusHours(1), PageRequest.of(0, 50));
        Map<Long, CrowdPrediction> perBus = new LinkedHashMap<>();
        high.forEach(p -> perBus.putIfAbsent(p.getBus().getId(), p));
        return perBus.values().stream().limit(10).map(this::convertToDto).toList();
    }

    // ---------------------------------------------------------------- helpers

    ModelInput buildInput(Route route, int capacity, LocalDateTime when) {
        int stopCount = route.getStops() != null ? route.getStops().size() : 0;
        Double distanceKm = route.getDistance();
        if (distanceKm == null && RoutePath.isUsable(route.getStops())) {
            distanceKm = RoutePath.of(route).lengthMeters() / 1000.0;
        }
        return new ModelInput(
                when.getHour(),
                when.getDayOfWeek().getValue() - 1, // Monday = 0, as in the training data
                distanceKm,
                stopCount >= 2 ? stopCount : null,   // unknown -> imputed by the model pipeline
                capacity);
    }

    private int capacityOf(Bus bus) {
        return bus.getCapacity() != null && bus.getCapacity() > 0 ? bus.getCapacity() : DEFAULT_CAPACITY;
    }

    private int representativeCapacity(Long routeId) {
        return (int) Math.round(busRepository.findByRouteId(routeId).stream()
                .map(Bus::getCapacity).filter(Objects::nonNull)
                .mapToInt(Integer::intValue).average().orElse(DEFAULT_CAPACITY));
    }

    static double crowdScore(Map<String, Double> probabilities) {
        return probabilities.getOrDefault("MEDIUM", 0.0) * 0.5 + probabilities.getOrDefault("HIGH", 0.0);
    }

    private List<HourlyForecastDto.HistoricalHour> historyByHour(Long routeId) {
        Map<Integer, HourlyForecastDto.HistoricalHour> byHour = new TreeMap<>();
        for (Object[] row : crowdPredictionRepository.countByHourAndLevel(routeId, LocalDateTime.now().minusDays(30))) {
            int hour = ((Number) row[0]).intValue();
            CrowdLevel level = (CrowdLevel) row[1];
            long count = ((Number) row[2]).longValue();
            HourlyForecastDto.HistoricalHour h = byHour.computeIfAbsent(hour,
                    k -> HourlyForecastDto.HistoricalHour.builder().hour(k).build());
            switch (level) {
                case LOW -> h.setLow(h.getLow() + count);
                case MEDIUM -> h.setMedium(h.getMedium() + count);
                case HIGH, FULL -> h.setHigh(h.getHigh() + count);
            }
        }
        return new ArrayList<>(byHour.values());
    }

    private CrowdPrediction toEntity(Bus bus, ModelOutput output, LocalDateTime madeAt, LocalDateTime travelTime, String source) {
        Map<String, Double> p = output.probabilities();
        return CrowdPrediction.builder()
                .bus(bus)
                .route(bus.getRoute())
                .crowdLevel(CrowdLevel.valueOf(output.crowdLevel()))
                .confidence(output.confidence())
                .probabilityLow(p.get("LOW"))
                .probabilityMedium(p.get("MEDIUM"))
                .probabilityHigh(p.get("HIGH"))
                .modelVersion(output.modelVersion())
                .modelKind(CrowdPrediction.KIND_TIME_ROUTE)
                .horizonMinutes((int) Math.max(0, java.time.Duration.between(madeAt, travelTime).toMinutes()))
                .timestamp(madeAt)
                .predictedFor(travelTime)
                .source(source)
                .build();
    }

    public CrowdPredictionDto convertToDto(CrowdPrediction prediction) {
        Map<String, Double> probabilities = null;
        if (prediction.getProbabilityLow() != null) {
            probabilities = new LinkedHashMap<>();
            probabilities.put("LOW", prediction.getProbabilityLow());
            probabilities.put("MEDIUM", prediction.getProbabilityMedium());
            probabilities.put("HIGH", prediction.getProbabilityHigh());
            if (prediction.getProbabilityFull() != null) {
                probabilities.put("FULL", prediction.getProbabilityFull());
            }
        }
        Bus bus = prediction.getBus();
        Route route = prediction.getRoute();
        return CrowdPredictionDto.builder()
                .id(prediction.getId())
                .busId(bus != null ? bus.getId() : null)
                .busNumber(bus != null ? bus.getBusNumber() : null)
                .busCapacity(bus != null ? bus.getCapacity() : null)
                .routeId(route != null ? route.getId() : null)
                .routeSource(route != null ? route.getSource() : null)
                .routeDestination(route != null ? route.getDestination() : null)
                .crowdLevel(prediction.getCrowdLevel())
                .timestamp(prediction.getTimestamp())
                .predictedFor(prediction.getPredictedFor())
                .confidence(prediction.getConfidence())
                .probabilities(probabilities)
                .modelVersion(prediction.getModelVersion())
                .source(prediction.getSource())
                .modelKind(prediction.getModelKind())
                .horizonMinutes(prediction.getHorizonMinutes())
                .predictedPassengers(prediction.getPredictedPassengers())
                .inputOnboard(prediction.getInputOnboard())
                .build();
    }

    /** Count of latest-per-bus levels, used by dashboards. */
    @Transactional(readOnly = true)
    public Map<String, Long> currentCrowdSummary() {
        Map<String, Long> summary = new LinkedHashMap<>();
        for (CrowdLevel level : CrowdLevel.values()) {
            summary.put(level.name(), 0L);
        }
        Map<Long, CrowdPrediction> latest = latestPerBus();
        latest.values().forEach(p -> summary.merge(p.getCrowdLevel().name(), 1L, Long::sum));
        return summary;
    }

    /** Latest prediction of every bus, keyed by bus id (ties on timestamp resolved by highest id). */
    @Transactional(readOnly = true)
    public Map<Long, CrowdPrediction> latestPerBus() {
        return crowdPredictionRepository.findLatestPerBus().stream()
                .collect(Collectors.toMap(p -> p.getBus().getId(), p -> p,
                        (a, b) -> a.getId() > b.getId() ? a : b));
    }
}
