package com.transport.backend.controller;

import com.transport.backend.dto.*;
import com.transport.backend.entity.Sensor;
import com.transport.backend.service.occupancy.LiveForecastService;
import com.transport.backend.service.occupancy.OccupancyService;
import com.transport.backend.service.occupancy.SensorService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import com.transport.backend.exception.BadRequestException;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Valid;
import jakarta.validation.Validator;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Set;

/**
 * Live passenger occupancy.
 *  - Devices POST reports to /api/sensors/observations with headers X-Sensor-Code and X-Sensor-Key.
 *  - Users read occupancy; administrators read the observation log and reconcile counts.
 * Every change is pushed on WebSocket /topic/occupancy.
 */
@RestController
@Tag(name = "Occupancy & sensors", description = "Passenger counts from cameras/sensors, seat availability, reconciliation")
public class OccupancyController {

    public static final String OCCUPANCY_TOPIC = "/topic/occupancy";
    public static final String CROWD_TOPIC = "/topic/crowd";

    private final OccupancyService occupancyService;
    private final SensorService sensorService;
    private final LiveForecastService liveForecastService;
    private final SimpMessagingTemplate messagingTemplate;
    private final Validator validator;

    public OccupancyController(OccupancyService occupancyService, SensorService sensorService,
                               LiveForecastService liveForecastService, SimpMessagingTemplate messagingTemplate,
                               Validator validator) {
        this.validator = validator;
        this.occupancyService = occupancyService;
        this.sensorService = sensorService;
        this.liveForecastService = liveForecastService;
        this.messagingTemplate = messagingTemplate;
    }

    @PostMapping("/api/sensors/observations")
    @Operation(summary = "Device report (people visible, door entries/exits since last report, occupied seats, health)",
            description = "Authenticated with the sensor's own key, not a user JWT. Duplicate sequence numbers are ignored.")
    public ResponseEntity<ObservationResultDto> report(
            @Parameter(description = "Sensor code") @RequestHeader(value = "X-Sensor-Code", required = false) String code,
            @Parameter(description = "Sensor secret key") @RequestHeader(value = "X-Sensor-Key", required = false) String key,
            @RequestBody ObservationRequest request) {
        // Authenticate the device BEFORE validating the payload, so unknown callers learn nothing.
        Sensor sensor = sensorService.authenticate(code, key);
        Set<ConstraintViolation<ObservationRequest>> violations = validator.validate(request);
        if (!violations.isEmpty()) {
            throw new BadRequestException(violations.stream().map(ConstraintViolation::getMessage).sorted().findFirst().orElse("Invalid report"));
        }
        ObservationResultDto result = occupancyService.ingest(sensor, request);
        if (!"DUPLICATE_IGNORED".equals(result.getStatus())) {
            liveForecastService.forecastIfDue(sensor.getBus().getId())
                    .ifPresent(forecast -> {
                        result.getOccupancy().setLiveForecast(forecast);
                        messagingTemplate.convertAndSend(CROWD_TOPIC, forecast);
                    });
            messagingTemplate.convertAndSend(OCCUPANCY_TOPIC, result.getOccupancy());
        }
        return ResponseEntity.ok(result);
    }

    @GetMapping("/api/occupancy")
    @Operation(summary = "Occupancy of every bus (estimated onboard, remaining capacity, seats where measured)")
    public ResponseEntity<List<OccupancyDto>> all() {
        return ResponseEntity.ok(occupancyService.listAll());
    }

    @GetMapping("/api/occupancy/{busId}")
    @Operation(summary = "Occupancy of one bus")
    public ResponseEntity<OccupancyDto> one(@PathVariable Long busId) {
        return ResponseEntity.ok(occupancyService.get(busId));
    }

    @GetMapping("/api/occupancy/{busId}/observations")
    @Operation(summary = "Observation and reconciliation log of a bus (ADMIN)")
    public ResponseEntity<PageResponse<ObservationDto>> observations(@PathVariable Long busId,
                                                                     @RequestParam(defaultValue = "0") int page,
                                                                     @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(occupancyService.observations(busId, page, size));
    }

    @PutMapping("/api/occupancy/{busId}/reconcile")
    @Operation(summary = "Manually set the onboard count (and optionally occupied seats) after a head count (ADMIN)")
    public ResponseEntity<OccupancyDto> reconcile(@PathVariable Long busId, @Valid @RequestBody ReconcileRequest request,
                                                  Authentication authentication) {
        OccupancyDto dto = occupancyService.reconcile(busId, request, authentication.getName());
        messagingTemplate.convertAndSend(OCCUPANCY_TOPIC, dto);
        return ResponseEntity.ok(dto);
    }
}
