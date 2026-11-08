package com.transport.backend.service.occupancy;

import com.transport.backend.dto.*;
import com.transport.backend.entity.*;
import com.transport.backend.exception.BadRequestException;
import com.transport.backend.exception.ResourceNotFoundException;
import com.transport.backend.repository.*;
import com.transport.backend.service.CrowdPredictionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;

/**
 * Live passenger occupancy (onboard estimate) of each bus, built from sensor reports.
 *
 * Door cameras send entries/exits since their previous report:
 *     updated onboard = previous onboard + entries - exits     (never below 0)
 * Rules:
 *  - a report whose (sensor, sequenceNumber) was already received is a DUPLICATE and changes nothing;
 *  - a report older than the newest one is still added (sums do not depend on order) and marked APPLIED_LATE;
 *  - a jump in sequence numbers means reports were lost -> the estimate is flagged for reconciliation;
 *  - a result below 0 is clamped to 0 and flagged; an unknown starting count is assumed 0 and flagged;
 *  - an unavailable door camera may have missed passengers -> flagged.
 * Cabin cameras report people VISIBLE in view: a lower bound, used as the estimate only when the bus
 * has no door counter. Seat sensors (or a manual count) are the only source of occupied seats.
 */
@Service
public class OccupancyService {

    private static final Logger log = LoggerFactory.getLogger(OccupancyService.class);
    private static final int VISIBLE_MISMATCH_TOLERANCE = 2;

    private final BusRepository busRepository;
    private final SensorRepository sensorRepository;
    private final BusOccupancyRepository occupancyRepository;
    private final PassengerCountObservationRepository observationRepository;
    private final CrowdPredictionRepository predictionRepository;
    private final CrowdPredictionService crowdPredictionService;
    private final SensorService sensorService;
    private final CrowdThresholds thresholds;
    private final long staleAfterSeconds;
    private final long seatValidSeconds;

    public OccupancyService(BusRepository busRepository, SensorRepository sensorRepository,
                            BusOccupancyRepository occupancyRepository, PassengerCountObservationRepository observationRepository,
                            CrowdPredictionRepository predictionRepository, CrowdPredictionService crowdPredictionService,
                            SensorService sensorService, CrowdThresholds thresholds,
                            @Value("${app.occupancy.stale-after-seconds:120}") long staleAfterSeconds,
                            @Value("${app.occupancy.seat-valid-seconds:300}") long seatValidSeconds) {
        this.busRepository = busRepository;
        this.sensorRepository = sensorRepository;
        this.occupancyRepository = occupancyRepository;
        this.observationRepository = observationRepository;
        this.predictionRepository = predictionRepository;
        this.crowdPredictionService = crowdPredictionService;
        this.sensorService = sensorService;
        this.thresholds = thresholds;
        this.staleAfterSeconds = staleAfterSeconds;
        this.seatValidSeconds = seatValidSeconds;
    }

    // ------------------------------------------------------------------ ingestion

    @Transactional
    public ObservationResultDto ingest(Sensor sensorRef, ObservationRequest req) {
        LocalDateTime now = LocalDateTime.now();
        if (req.getObservedAt().isAfter(now.plusMinutes(2))) {
            throw new BadRequestException("observedAt is in the future (check the device clock)");
        }
        if (req.getObservedAt().isBefore(now.minusHours(24))) {
            throw new BadRequestException("observedAt is more than 24 hours old");
        }
        Sensor sensor = sensorRepository.findById(sensorRef.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Sensor", "id", sensorRef.getId()));
        Bus bus = sensor.getBus();

        Optional<PassengerCountObservation> existing =
                observationRepository.findBySensorIdAndSequenceNumber(sensor.getId(), req.getSequenceNumber());
        if (existing.isPresent()) {
            return ObservationResultDto.builder().observationId(existing.get().getId())
                    .status("DUPLICATE_IGNORED").occupancy(buildDto(bus, now)).build();
        }

        BusOccupancy state = occupancyRepository.findByBusIdForUpdate(bus.getId())
                .orElseGet(() -> BusOccupancy.builder().bus(bus).needsReconciliation(false).build());
        Long lastSeq = sensor.getLastSequence();
        boolean late = lastSeq != null && req.getSequenceNumber() < lastSeq;
        long missing = lastSeq != null && req.getSequenceNumber() > lastSeq + 1 ? req.getSequenceNumber() - lastSeq - 1 : 0;

        PassengerCountObservation obs = PassengerCountObservation.builder()
                .sensor(sensor).bus(bus)
                .sequenceNumber(req.getSequenceNumber())
                .observedAt(req.getObservedAt()).receivedAt(now)
                .visibleCount(req.getVisibleCount()).entries(req.getEntries()).exits(req.getExits())
                .occupiedSeats(req.getOccupiedSeats())
                .meanConfidence(req.getMeanConfidence()).frameQuality(req.getFrameQuality())
                .health(req.getHealth()).sourceMode(req.getMode())
                .previousOnboard(state.getEstimatedOnboard())
                .build();

        boolean failed = req.getHealth() == SensorHealth.CAMERA_UNAVAILABLE || req.getHealth() == SensorHealth.INFERENCE_ERROR;
        String status;
        if (failed) {
            status = PassengerCountObservation.STATUS_HEALTH_ONLY;
            if (sensor.getType() == SensorType.DOOR_CAMERA) {
                flag(state, "Door camera " + sensor.getSensorCode() + " reported " + req.getHealth()
                        + ": passengers may have been missed");
            }
        } else {
            status = switch (sensor.getType()) {
                case DOOR_CAMERA -> applyDoorCounts(state, sensor, req, late);
                case CABIN_CAMERA -> applyCabinSnapshot(state, bus, sensor, req, late);
                case SEAT_SENSOR -> applySeatSnapshot(state, bus, req, late);
            };
        }
        if (missing > 0) {
            flag(state, missing + " report(s) from " + sensor.getSensorCode() + " are missing (sequence "
                    + (lastSeq + 1) + "-" + (req.getSequenceNumber() - 1) + ")");
        }
        obs.setStatus(status);
        obs.setUpdatedOnboard(state.getEstimatedOnboard());

        sensor.setLastSeenAt(now);
        sensor.setLastHealth(req.getHealth());
        sensor.setLastMessage(req.getMessage());
        sensor.setLastMode(req.getMode());
        sensor.setDetectorModel(req.getDetectorModel());
        sensor.setLastSequence(lastSeq == null ? req.getSequenceNumber() : Math.max(lastSeq, req.getSequenceNumber()));

        occupancyRepository.save(state);
        sensorRepository.save(sensor);
        PassengerCountObservation saved = observationRepository.save(obs);
        return ObservationResultDto.builder().observationId(saved.getId()).status(status).occupancy(buildDto(bus, now)).build();
    }

    private String applyDoorCounts(BusOccupancy state, Sensor sensor, ObservationRequest req, boolean late) {
        if (req.getEntries() == null || req.getExits() == null) {
            throw new BadRequestException("Door camera reports must include entries and exits");
        }
        Integer previous = state.getEstimatedOnboard();
        if (previous == null) {
            previous = 0;
            flag(state, "Starting onboard count was unknown and assumed 0; confirm with a manual count");
        }
        int raw = previous + req.getEntries() - req.getExits();
        String status = late ? PassengerCountObservation.STATUS_APPLIED_LATE : PassengerCountObservation.STATUS_APPLIED;
        if (raw < 0) {
            flag(state, "Door counts gave a negative onboard estimate (" + raw + "); clamped to 0");
            raw = 0;
            status = PassengerCountObservation.STATUS_CLAMPED_NEGATIVE;
        }
        state.setEstimatedOnboard(raw);
        state.setOnboardMethod(BusOccupancy.METHOD_DOOR_COUNT);
        state.setDataMode(req.getMode());
        state.setConfidence(confidenceOf(req, state.isNeedsReconciliation()));
        if (state.getUpdatedAt() == null || req.getObservedAt().isAfter(state.getUpdatedAt())) {
            state.setUpdatedAt(req.getObservedAt());
        }
        return status;
    }

    private String applyCabinSnapshot(BusOccupancy state, Bus bus, Sensor sensor, ObservationRequest req, boolean late) {
        if (req.getVisibleCount() == null) {
            throw new BadRequestException("Cabin camera reports must include visibleCount");
        }
        if (late) {
            return PassengerCountObservation.STATUS_SNAPSHOT; // older than what we already have
        }
        state.setLastVisibleCount(req.getVisibleCount());
        state.setLastVisibleAt(req.getObservedAt());
        boolean hasDoorCounter = sensorRepository.findByBusId(bus.getId()).stream()
                .anyMatch(s -> s.isEnabled() && s.getType() == SensorType.DOOR_CAMERA);
        if (!hasDoorCounter) {
            state.setEstimatedOnboard(req.getVisibleCount());
            state.setOnboardMethod(BusOccupancy.METHOD_CABIN_VISIBLE);
            state.setDataMode(req.getMode());
            state.setConfidence(confidenceOf(req, state.isNeedsReconciliation()));
            state.setUpdatedAt(req.getObservedAt());
        } else if (state.getEstimatedOnboard() != null
                && req.getVisibleCount() > state.getEstimatedOnboard() + VISIBLE_MISMATCH_TOLERANCE) {
            flag(state, "Cabin camera " + sensor.getSensorCode() + " sees " + req.getVisibleCount()
                    + " people but the door count is " + state.getEstimatedOnboard());
        }
        return PassengerCountObservation.STATUS_SNAPSHOT;
    }

    private String applySeatSnapshot(BusOccupancy state, Bus bus, ObservationRequest req, boolean late) {
        if (req.getOccupiedSeats() == null) {
            throw new BadRequestException("Seat sensor reports must include occupiedSeats");
        }
        if (bus.getSeatCount() == null) {
            throw new BadRequestException("Bus " + bus.getBusNumber() + " has no seat count configured");
        }
        if (req.getOccupiedSeats() > bus.getSeatCount()) {
            throw new BadRequestException("occupiedSeats exceeds the " + bus.getSeatCount() + " seats of bus " + bus.getBusNumber());
        }
        if (!late) {
            state.setOccupiedSeats(req.getOccupiedSeats());
            state.setSeatSource(PassengerCountObservation.MODE_SEAT_SENSOR);
            state.setSeatUpdatedAt(req.getObservedAt());
        }
        return PassengerCountObservation.STATUS_SNAPSHOT;
    }

    private static double confidenceOf(ObservationRequest req, boolean flagged) {
        double c = Math.min(req.getMeanConfidence() != null ? req.getMeanConfidence() : 0.5,
                req.getFrameQuality() != null ? req.getFrameQuality() : 1.0);
        if (req.getHealth() == SensorHealth.DEGRADED) c *= 0.7;
        if (flagged) c *= 0.8;
        return Math.round(c * 100) / 100.0;
    }

    private static void flag(BusOccupancy state, String reason) {
        state.setNeedsReconciliation(true);
        state.setReconciliationReason(reason.length() > 250 ? reason.substring(0, 250) : reason);
        log.info("Occupancy of bus {} flagged: {}", state.getBus().getBusNumber(), reason);
    }

    // ------------------------------------------------------------------ manual reconciliation

    @Transactional
    public OccupancyDto reconcile(Long busId, ReconcileRequest req, String adminEmail) {
        Bus bus = busRepository.findById(busId).orElseThrow(() -> new ResourceNotFoundException("Bus", "id", busId));
        if (req.getOccupiedSeats() != null) {
            if (bus.getSeatCount() == null) {
                throw new BadRequestException("Configure the seat count of " + bus.getBusNumber() + " before entering occupied seats");
            }
            if (req.getOccupiedSeats() > bus.getSeatCount()) {
                throw new BadRequestException("Occupied seats cannot exceed the " + bus.getSeatCount() + " seats");
            }
            if (req.getOccupiedSeats() > req.getOnboard()) {
                throw new BadRequestException("Occupied seats cannot exceed the number of passengers onboard");
            }
        }
        LocalDateTime now = LocalDateTime.now();
        BusOccupancy state = occupancyRepository.findByBusIdForUpdate(busId)
                .orElseGet(() -> BusOccupancy.builder().bus(bus).build());
        Integer previous = state.getEstimatedOnboard();

        state.setEstimatedOnboard(req.getOnboard());
        state.setOnboardMethod(BusOccupancy.METHOD_MANUAL);
        state.setDataMode(PassengerCountObservation.MODE_MANUAL);
        state.setConfidence(1.0);
        state.setUpdatedAt(now);
        state.setNeedsReconciliation(false);
        state.setReconciliationReason(null);
        if (req.getOccupiedSeats() != null) {
            state.setOccupiedSeats(req.getOccupiedSeats());
            state.setSeatSource(PassengerCountObservation.MODE_MANUAL);
            state.setSeatUpdatedAt(now);
        }
        occupancyRepository.save(state);
        observationRepository.save(PassengerCountObservation.builder()
                .bus(bus).observedAt(now).receivedAt(now)
                .occupiedSeats(req.getOccupiedSeats())
                .sourceMode(PassengerCountObservation.MODE_MANUAL)
                .status(PassengerCountObservation.STATUS_MANUAL)
                .previousOnboard(previous).updatedOnboard(req.getOnboard())
                .performedBy(adminEmail).note(req.getNote().trim())
                .build());
        log.info("Admin {} reconciled bus {}: {} -> {}", adminEmail, bus.getBusNumber(), previous, req.getOnboard());
        return buildDto(bus, now);
    }

    // ------------------------------------------------------------------ queries

    @Transactional(readOnly = true)
    public List<OccupancyDto> listAll() {
        LocalDateTime now = LocalDateTime.now();
        return busRepository.findAll().stream()
                .sorted(Comparator.comparing(Bus::getBusNumber, String.CASE_INSENSITIVE_ORDER))
                .map(b -> buildDto(b, now)).toList();
    }

    @Transactional(readOnly = true)
    public OccupancyDto get(Long busId) {
        Bus bus = busRepository.findById(busId).orElseThrow(() -> new ResourceNotFoundException("Bus", "id", busId));
        return buildDto(bus, LocalDateTime.now());
    }

    @Transactional(readOnly = true)
    public PageResponse<ObservationDto> observations(Long busId, int page, int size) {
        if (!busRepository.existsById(busId)) {
            throw new ResourceNotFoundException("Bus", "id", busId);
        }
        var result = observationRepository.findByBusIdOrderByReceivedAtDesc(busId,
                PageRequest.of(Math.max(0, page), Math.min(Math.max(1, size), 100)));
        return PageResponse.of(result, this::toObservationDto);
    }

    /** Fresh onboard estimate of a bus (for the live forecast), or empty if unknown or stale. */
    @Transactional(readOnly = true)
    public Optional<BusOccupancy> freshState(Long busId) {
        return occupancyRepository.findByBusId(busId)
                .filter(s -> s.getEstimatedOnboard() != null && s.getUpdatedAt() != null
                        && s.getUpdatedAt().isAfter(LocalDateTime.now().minusSeconds(staleAfterSeconds)));
    }

    public OccupancyDto buildDto(Bus bus, LocalDateTime now) {
        List<Sensor> sensors = sensorRepository.findByBusId(bus.getId());
        BusOccupancy state = occupancyRepository.findByBusId(bus.getId()).orElse(null);
        OccupancyDto.OccupancyDtoBuilder dto = OccupancyDto.builder()
                .busId(bus.getId())
                .busNumber(bus.getBusNumber())
                .routeId(bus.getRoute() != null ? bus.getRoute().getId() : null)
                .capacity(bus.getCapacity())
                .seatCount(bus.getSeatCount())
                .sensors(sensors.stream().map(s -> sensorService.toDto(s, null)).toList())
                .seatDataStatus("UNKNOWN");

        // Seats come only from seat sensors or a manual count, independently of the passenger estimate.
        boolean seatsValid = state != null && state.getOccupiedSeats() != null && state.getSeatUpdatedAt() != null
                && state.getSeatUpdatedAt().isAfter(now.minusSeconds(seatValidSeconds));
        Integer occupiedSeats = seatsValid ? state.getOccupiedSeats() : null;
        if (seatsValid) {
            dto.occupiedSeats(occupiedSeats)
                    .availableSeats(OccupancyMath.availableSeats(bus.getSeatCount(), occupiedSeats))
                    .seatDataStatus(PassengerCountObservation.MODE_MANUAL.equals(state.getSeatSource()) ? "MANUAL" : "MEASURED")
                    .seatUpdatedAt(state.getSeatUpdatedAt());
        }

        if (state == null || state.getEstimatedOnboard() == null) {
            return dto.dataStatus(sensors.isEmpty() ? "NO_SENSOR" : "NO_DATA")
                    .needsReconciliation(state != null && state.isNeedsReconciliation())
                    .reconciliationReason(state != null ? state.getReconciliationReason() : null)
                    .build();
        }

        boolean stale = state.getUpdatedAt() == null || state.getUpdatedAt().isBefore(now.minusSeconds(staleAfterSeconds));
        Integer onboard = state.getEstimatedOnboard();
        Double percent = OccupancyMath.occupancyPercent(bus.getCapacity(), onboard);

        dto.dataStatus(stale ? "STALE" : "LIVE")
                .method(state.getOnboardMethod())
                .dataMode(state.getDataMode())
                .estimatedOnboard(onboard)
                .onboardIsLowerBound(BusOccupancy.METHOD_CABIN_VISIBLE.equals(state.getOnboardMethod()))
                .confidence(state.getConfidence())
                .availableCapacity(OccupancyMath.availableCapacity(bus.getCapacity(), onboard))
                .occupancyPercent(percent)
                .crowdCategory(percent != null ? thresholds.categorize(percent / 100.0) : null)
                .overCapacity(OccupancyMath.overCapacity(bus.getCapacity(), onboard))
                .standingPassengers(OccupancyMath.standingPassengers(onboard, occupiedSeats))
                .lastVisibleCount(state.getLastVisibleCount())
                .lastVisibleAt(state.getLastVisibleAt())
                .updatedAt(state.getUpdatedAt())
                .needsReconciliation(state.isNeedsReconciliation())
                .reconciliationReason(state.getReconciliationReason());

        if (!stale) {
            predictionRepository.findTopByBusIdAndModelKindOrderByTimestampDesc(bus.getId(), CrowdPrediction.KIND_LIVE_FORECAST)
                    .filter(p -> p.getTimestamp().isAfter(now.minusMinutes(10)))
                    .map(crowdPredictionService::convertToDto)
                    .ifPresent(dto::liveForecast);
        }
        return dto.build();
    }

    private ObservationDto toObservationDto(PassengerCountObservation o) {
        return ObservationDto.builder()
                .id(o.getId())
                .sensorCode(o.getSensor() != null ? o.getSensor().getSensorCode() : null)
                .sequenceNumber(o.getSequenceNumber())
                .observedAt(o.getObservedAt()).receivedAt(o.getReceivedAt())
                .visibleCount(o.getVisibleCount()).entries(o.getEntries()).exits(o.getExits())
                .occupiedSeats(o.getOccupiedSeats())
                .meanConfidence(o.getMeanConfidence()).frameQuality(o.getFrameQuality())
                .health(o.getHealth()).sourceMode(o.getSourceMode()).status(o.getStatus())
                .previousOnboard(o.getPreviousOnboard()).updatedOnboard(o.getUpdatedOnboard())
                .performedBy(o.getPerformedBy()).note(o.getNote())
                .build();
    }
}
