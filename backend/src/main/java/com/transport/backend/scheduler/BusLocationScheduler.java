package com.transport.backend.scheduler;

import com.transport.backend.dto.CrowdPredictionDto;
import com.transport.backend.dto.LocationDto;
import com.transport.backend.entity.CrowdPrediction;
import com.transport.backend.exception.ServiceUnavailableException;
import com.transport.backend.repository.CrowdPredictionRepository;
import com.transport.backend.repository.LocationRepository;
import com.transport.backend.service.BusSimulationService;
import com.transport.backend.service.CrowdPredictionService;
import com.transport.backend.service.LocationService;
import com.transport.backend.service.occupancy.LiveForecastService;
import com.transport.backend.service.occupancy.ModelRegistryService;
import com.transport.backend.service.occupancy.PassengerSimulationService;
import com.transport.backend.controller.OccupancyController;
import com.transport.backend.repository.PassengerCountObservationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Background jobs. fixedDelay (not fixedRate) guarantees a run never overlaps the previous one.
 *  1. every app.simulation.interval-ms (5 s): move simulated buses and broadcast on /topic/location
 *  2. every app.crowd.prediction-interval-ms (5 min): ML crowd prediction for every active bus (time-and-route model),
 *     plus a 15-minute live forecast for buses with fresh occupancy data; broadcast on /topic/crowd
 *  2b. every app.simulation.passengers.interval-ms (30 s): SIMULATED passenger counts for buses without a sensor,
 *     broadcast on /topic/occupancy
 *  3. hourly: delete old location history, scheduled predictions and occupancy observations; record ML model metadata
 */
@Component
public class BusLocationScheduler {

    public static final String CROWD_TOPIC = "/topic/crowd";
    private static final Logger log = LoggerFactory.getLogger(BusLocationScheduler.class);

    private final BusSimulationService simulationService;
    private final CrowdPredictionService crowdPredictionService;
    private final LocationRepository locationRepository;
    private final CrowdPredictionRepository crowdPredictionRepository;
    private final SimpMessagingTemplate messagingTemplate;
    private final LiveForecastService liveForecastService;
    private final ModelRegistryService modelRegistryService;
    private final PassengerCountObservationRepository observationRepository;
    private final PassengerSimulationService passengerSimulationService;
    private final boolean simulationEnabled;
    private final int observationRetentionDays;
    private final int locationRetentionHours;
    private final int predictionRetentionDays;

    private boolean mlWasDown;

    public BusLocationScheduler(BusSimulationService simulationService,
                                CrowdPredictionService crowdPredictionService,
                                LocationRepository locationRepository,
                                CrowdPredictionRepository crowdPredictionRepository,
                                SimpMessagingTemplate messagingTemplate,
                                LiveForecastService liveForecastService,
                                ModelRegistryService modelRegistryService,
                                PassengerCountObservationRepository observationRepository,
                                PassengerSimulationService passengerSimulationService,
                                @Value("${app.occupancy.retention-days:30}") int observationRetentionDays,
                                @Value("${app.simulation.enabled:true}") boolean simulationEnabled,
                                @Value("${app.location.retention-hours:24}") int locationRetentionHours,
                                @Value("${app.crowd.scheduled-retention-days:30}") int predictionRetentionDays) {
        this.simulationService = simulationService;
        this.crowdPredictionService = crowdPredictionService;
        this.locationRepository = locationRepository;
        this.crowdPredictionRepository = crowdPredictionRepository;
        this.messagingTemplate = messagingTemplate;
        this.liveForecastService = liveForecastService;
        this.modelRegistryService = modelRegistryService;
        this.observationRepository = observationRepository;
        this.passengerSimulationService = passengerSimulationService;
        this.observationRetentionDays = observationRetentionDays;
        this.simulationEnabled = simulationEnabled;
        this.locationRetentionHours = locationRetentionHours;
        this.predictionRetentionDays = predictionRetentionDays;
    }

    @Scheduled(initialDelay = 5000, fixedDelayString = "${app.simulation.interval-ms:5000}")
    public void simulateBusMovement() {
        if (!simulationEnabled) {
            return;
        }
        try {
            List<LocationDto> updates = simulationService.tick();
            updates.forEach(update -> messagingTemplate.convertAndSend(LocationService.LOCATION_TOPIC, update));
            log.debug("Simulated GPS update for {} buses", updates.size());
        } catch (Exception ex) {
            log.error("Bus simulation tick failed", ex);
        }
    }

    @Scheduled(initialDelay = 8000, fixedDelayString = "${app.simulation.passengers.interval-ms:30000}")
    public void simulatePassengers() {
        try {
            passengerSimulationService.tick()
                    .forEach(o -> messagingTemplate.convertAndSend(OccupancyController.OCCUPANCY_TOPIC, o));
        } catch (Exception ex) {
            log.error("Passenger simulation failed", ex);
        }
    }

    @Scheduled(initialDelay = 20000, fixedDelayString = "${app.crowd.prediction-interval-ms:300000}")
    public void predictCrowdForActiveBuses() {
        try {
            List<CrowdPredictionDto> predictions = crowdPredictionService.predictForActiveBuses();
            predictions.forEach(p -> messagingTemplate.convertAndSend(CROWD_TOPIC, p));
            List<CrowdPredictionDto> live = liveForecastService.forecastAllFresh();
            live.forEach(p -> messagingTemplate.convertAndSend(CROWD_TOPIC, p));
            if (mlWasDown) {
                log.info("Crowd prediction service is reachable again");
                mlWasDown = false;
            }
            log.info("Scheduled crowd prediction stored for {} active buses ({} live 15-minute forecasts)",
                    predictions.size(), live.size());
        } catch (ServiceUnavailableException ex) {
            if (!mlWasDown) { // log once per outage, not every run
                log.warn("Skipping scheduled crowd predictions: {}", ex.getMessage());
                mlWasDown = true;
            }
        } catch (Exception ex) {
            log.error("Scheduled crowd prediction failed", ex);
        }
    }

    @Scheduled(cron = "0 7 * * * *")
    @Transactional
    public void cleanUpOldData() {
        int locations = locationRepository.deleteOlderThan(LocalDateTime.now().minusHours(locationRetentionHours));
        int predictions = crowdPredictionRepository.deleteBySourceOlderThan(
                CrowdPrediction.SOURCE_SCHEDULED, LocalDateTime.now().minusDays(predictionRetentionDays));
        int observations = observationRepository.deleteOlderThan(LocalDateTime.now().minusDays(observationRetentionDays));
        log.info("Retention cleanup removed {} location records, {} scheduled predictions and {} occupancy observations",
                locations, predictions, observations);
    }

    /** Records the ML model versions and their metrics in ml_models (startup and hourly). */
    @Scheduled(initialDelay = 15000, fixedDelay = 3600000)
    public void syncModelRegistry() {
        modelRegistryService.syncFromMlService();
    }
}
