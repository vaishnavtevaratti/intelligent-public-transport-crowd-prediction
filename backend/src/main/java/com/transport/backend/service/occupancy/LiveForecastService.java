package com.transport.backend.service.occupancy;

import com.transport.backend.dto.CrowdPredictionDto;
import com.transport.backend.dto.ForecastPerformanceDto;
import com.transport.backend.entity.*;
import com.transport.backend.exception.ServiceUnavailableException;
import com.transport.backend.repository.BusRepository;
import com.transport.backend.repository.CrowdPredictionRepository;
import com.transport.backend.repository.PassengerCountObservationRepository;
import com.transport.backend.service.CrowdPredictionService;
import com.transport.backend.service.geo.RoutePath;
import com.transport.backend.service.ml.CrowdModelClient;
import com.transport.backend.service.ml.CrowdModelClient.ForecastInput;
import com.transport.backend.service.ml.CrowdModelClient.ForecastOutput;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 15-minute-ahead crowd forecasts from LIVE occupancy (ml-service /forecast).
 * Only buses with a fresh onboard estimate get one; buses without live data keep using the
 * time-and-route model. Results are stored in crowd_predictions with modelKind = LIVE_FORECAST.
 */
@Service
public class LiveForecastService {

    public static final String SOURCE_LIVE = "LIVE";
    private static final Logger log = LoggerFactory.getLogger(LiveForecastService.class);

    private final BusRepository busRepository;
    private final OccupancyService occupancyService;
    private final PassengerCountObservationRepository observationRepository;
    private final CrowdPredictionRepository predictionRepository;
    private final CrowdPredictionService crowdPredictionService;
    private final CrowdModelClient modelClient;
    private final CrowdThresholds thresholds;

    public LiveForecastService(BusRepository busRepository, OccupancyService occupancyService,
                               PassengerCountObservationRepository observationRepository,
                               CrowdPredictionRepository predictionRepository, CrowdPredictionService crowdPredictionService,
                               CrowdModelClient modelClient, CrowdThresholds thresholds) {
        this.busRepository = busRepository;
        this.occupancyService = occupancyService;
        this.observationRepository = observationRepository;
        this.predictionRepository = predictionRepository;
        this.crowdPredictionService = crowdPredictionService;
        this.modelClient = modelClient;
        this.thresholds = thresholds;
    }

    /**
     * Forecast for one bus if its live data is fresh and no forecast was made in the last minute
     * (event-driven: called after each sensor report). Never throws; returns empty when skipped.
     */
    @Transactional
    public Optional<CrowdPredictionDto> forecastIfDue(Long busId) {
        boolean recent = predictionRepository.findTopByBusIdAndModelKindOrderByTimestampDesc(busId, CrowdPrediction.KIND_LIVE_FORECAST)
                .filter(p -> p.getTimestamp().isAfter(LocalDateTime.now().minusSeconds(60))).isPresent();
        if (recent) {
            return Optional.empty();
        }
        try {
            return busRepository.findById(busId).flatMap(bus -> forecast(List.of(bus)).stream().findFirst());
        } catch (RuntimeException ex) {
            // A forecast problem must never make the sensor report itself fail.
            log.warn("Live forecast skipped for bus {}: {}", busId, ex.getMessage());
            return Optional.empty();
        }
    }

    /** Scheduled job: forecasts for every active bus with fresh occupancy data. */
    @Transactional
    public List<CrowdPredictionDto> forecastAllFresh() {
        return forecast(busRepository.findByStatus(Bus.STATUS_ACTIVE));
    }

    private List<CrowdPredictionDto> forecast(List<Bus> candidates) {
        LocalDateTime now = LocalDateTime.now();
        List<Bus> buses = new ArrayList<>();
        List<ForecastInput> inputs = new ArrayList<>();
        List<Integer> onboards = new ArrayList<>();
        for (Bus bus : candidates) {
            if (bus.getRoute() == null || bus.getCapacity() == null) continue;
            Optional<BusOccupancy> state = occupancyService.freshState(bus.getId());
            if (state.isEmpty()) continue;
            int onboard = state.get().getEstimatedOnboard();
            Route route = bus.getRoute();
            Double distance = route.getDistance();
            if (distance == null && RoutePath.isUsable(route.getStops())) {
                distance = RoutePath.of(route).lengthMeters() / 1000.0;
            }
            int stops = route.getStops() != null ? route.getStops().size() : 0;
            inputs.add(new ForecastInput(now.getHour() * 60 + now.getMinute(), now.getDayOfWeek().getValue() - 1,
                    distance, stops >= 2 ? stops : null, bus.getCapacity(), Math.min(onboard, 600),
                    onboardAround(bus.getId(), now.minusMinutes(15)).map(v -> Math.min(v, 600)).orElse(null)));
            buses.add(bus);
            onboards.add(onboard);
        }
        if (inputs.isEmpty()) {
            return List.of();
        }
        List<ForecastOutput> outputs = modelClient.forecast(inputs);
        if (outputs == null || outputs.size() != inputs.size()) {
            throw new ServiceUnavailableException("Forecast service returned " + (outputs == null ? 0 : outputs.size())
                    + " results for " + inputs.size() + " buses");
        }
        List<CrowdPrediction> saved = new ArrayList<>();
        for (int i = 0; i < buses.size(); i++) {
            ForecastOutput out = outputs.get(i);
            if (out.extrapolated()) { // e.g. a 4-passenger demo bus: far outside the training data
                log.debug("No live forecast for bus {}: {} outside training range", buses.get(i).getBusNumber(), out.outOfTrainingRange());
                continue;
            }
            Map<String, Double> p = out.probabilities();
            saved.add(predictionRepository.save(CrowdPrediction.builder()
                    .bus(buses.get(i)).route(buses.get(i).getRoute())
                    .crowdLevel(CrowdLevel.valueOf(out.crowdLevel()))
                    .confidence(out.confidence())
                    .probabilityLow(p.get("LOW")).probabilityMedium(p.get("MEDIUM"))
                    .probabilityHigh(p.get("HIGH")).probabilityFull(p.get("FULL"))
                    .predictedPassengers(out.predictedPassengers())
                    .inputOnboard(onboards.get(i))
                    .horizonMinutes(out.horizonMinutes())
                    .modelKind(CrowdPrediction.KIND_LIVE_FORECAST)
                    .modelVersion(out.modelVersion())
                    .timestamp(now)
                    .predictedFor(now.plusMinutes(out.horizonMinutes()))
                    .source(SOURCE_LIVE)
                    .build()));
        }
        return saved.stream().map(crowdPredictionService::convertToDto).toList();
    }

    /** Onboard estimate recorded closest before `time` (within 8 minutes), if any. */
    private Optional<Integer> onboardAround(Long busId, LocalDateTime time) {
        return observationRepository.findOnboardBetween(busId, time.minusMinutes(8), time, PageRequest.of(0, 1))
                .stream().findFirst().map(PassengerCountObservation::getUpdatedOnboard);
    }

    /**
     * Compares live forecasts from the last `days` days whose target time has passed with the onboard
     * estimate actually recorded around that time (latest record within +/- 3 minutes).
     */
    @Transactional(readOnly = true)
    public ForecastPerformanceDto performance(int days) {
        LocalDateTime now = LocalDateTime.now();
        List<CrowdPrediction> forecasts = predictionRepository.findByModelKindAndPredictedForBetweenOrderByPredictedForAsc(
                CrowdPrediction.KIND_LIVE_FORECAST, now.minusDays(days), now.minusMinutes(1));
        int checked = 0, missing = 0, levelHits = 0;
        double absError = 0, persistenceError = 0;
        for (CrowdPrediction f : forecasts) {
            Optional<PassengerCountObservation> actual = observationRepository.findOnboardBetween(
                    f.getBus().getId(), f.getPredictedFor().minusMinutes(3), f.getPredictedFor().plusMinutes(3), PageRequest.of(0, 1))
                    .stream().findFirst();
            if (actual.isEmpty() || f.getPredictedPassengers() == null || f.getInputOnboard() == null) {
                missing++;
                continue;
            }
            int value = actual.get().getUpdatedOnboard();
            checked++;
            absError += Math.abs(f.getPredictedPassengers() - value);
            persistenceError += Math.abs(f.getInputOnboard() - value);
            Integer capacity = f.getBus().getCapacity();
            if (capacity != null && capacity > 0 && thresholds.categorize((double) value / capacity) == f.getCrowdLevel()) {
                levelHits++;
            }
        }
        return ForecastPerformanceDto.builder()
                .forecastsChecked(checked)
                .forecastsWithoutActual(missing)
                .meanAbsoluteError(checked > 0 ? Math.round(absError / checked * 100) / 100.0 : null)
                .persistenceMeanAbsoluteError(checked > 0 ? Math.round(persistenceError / checked * 100) / 100.0 : null)
                .levelAccuracy(checked > 0 ? Math.round(levelHits * 1000.0 / checked) / 1000.0 : null)
                .note(checked == 0
                        ? "No live forecast has reached its target time with a recorded onboard value yet."
                        : "Actual values are themselves sensor ESTIMATES (test video / camera / manual), not ground truth.")
                .build();
    }
}
