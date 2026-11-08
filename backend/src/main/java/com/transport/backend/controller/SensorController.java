package com.transport.backend.controller;

import com.transport.backend.dto.ForecastPerformanceDto;
import com.transport.backend.dto.MlModelDto;
import com.transport.backend.dto.SensorDto;
import com.transport.backend.dto.SensorRequest;
import com.transport.backend.service.occupancy.LiveForecastService;
import com.transport.backend.service.occupancy.ModelRegistryService;
import com.transport.backend.service.occupancy.SensorService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** Administrator endpoints for sensors and ML model monitoring (ROLE_ADMIN enforced for /api/admin/**). */
@RestController
@RequestMapping("/api/admin")
@Tag(name = "Administration", description = "User management and system analytics (ADMIN only)")
public class SensorController {

    private final SensorService sensorService;
    private final ModelRegistryService modelRegistryService;
    private final LiveForecastService liveForecastService;

    public SensorController(SensorService sensorService, ModelRegistryService modelRegistryService,
                            LiveForecastService liveForecastService) {
        this.sensorService = sensorService;
        this.modelRegistryService = modelRegistryService;
        this.liveForecastService = liveForecastService;
    }

    @GetMapping("/sensors")
    @Operation(summary = "List cameras/sensors with health")
    public ResponseEntity<List<SensorDto>> list() {
        return ResponseEntity.ok(sensorService.list());
    }

    @PostMapping("/sensors")
    @Operation(summary = "Register a sensor; the response contains its secret key ONCE")
    public ResponseEntity<SensorDto> create(@Valid @RequestBody SensorRequest request) {
        return new ResponseEntity<>(sensorService.create(request), HttpStatus.CREATED);
    }

    @PutMapping("/sensors/{id}")
    @Operation(summary = "Update bus, type, description or enabled flag of a sensor")
    public ResponseEntity<SensorDto> update(@PathVariable Long id, @Valid @RequestBody SensorRequest request) {
        return ResponseEntity.ok(sensorService.update(id, request));
    }

    @PostMapping("/sensors/{id}/rotate-key")
    @Operation(summary = "Issue a new secret key (the old key stops working); returned ONCE")
    public ResponseEntity<SensorDto> rotateKey(@PathVariable Long id) {
        return ResponseEntity.ok(sensorService.rotateKey(id));
    }

    @DeleteMapping("/sensors/{id}")
    @Operation(summary = "Delete a sensor (its past observations are kept)")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        sensorService.delete(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/models")
    @Operation(summary = "ML model versions used by the system, with their evaluation metrics")
    public ResponseEntity<List<MlModelDto>> models() {
        modelRegistryService.syncFromMlService();
        return ResponseEntity.ok(modelRegistryService.list());
    }

    @GetMapping("/forecast-performance")
    @Operation(summary = "Live 15-minute forecasts compared with the onboard estimates later recorded")
    public ResponseEntity<ForecastPerformanceDto> forecastPerformance(@RequestParam(defaultValue = "7") int days) {
        return ResponseEntity.ok(liveForecastService.performance(Math.min(Math.max(days, 1), 30)));
    }
}
