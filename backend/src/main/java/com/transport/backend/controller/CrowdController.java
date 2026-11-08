package com.transport.backend.controller;

import com.transport.backend.dto.CrowdPredictionDto;
import com.transport.backend.dto.HourlyForecastDto;
import com.transport.backend.dto.ModelStatusDto;
import com.transport.backend.dto.PageResponse;
import com.transport.backend.dto.PredictionRequest;
import com.transport.backend.entity.CrowdLevel;
import com.transport.backend.service.CrowdPredictionService;
import com.transport.backend.service.DashboardService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/crowd")
@Tag(name = "Crowd prediction", description = "ML crowd level predictions (LOW / MEDIUM / HIGH)")
public class CrowdController {

    private final CrowdPredictionService crowdPredictionService;
    private final DashboardService dashboardService;

    @Autowired
    public CrowdController(CrowdPredictionService crowdPredictionService, DashboardService dashboardService) {
        this.crowdPredictionService = crowdPredictionService;
        this.dashboardService = dashboardService;
    }

    @GetMapping("/history")
    @Operation(summary = "Stored predictions, newest first, with optional filters (paged)")
    public ResponseEntity<PageResponse<CrowdPredictionDto>> getHistory(
            @RequestParam(required = false) Long routeId,
            @RequestParam(required = false) Long busId,
            @RequestParam(required = false) CrowdLevel level,
            @RequestParam(required = false) String source,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(crowdPredictionService.getHistory(routeId, busId, level, source, page, size));
    }

    @PostMapping("/predict")
    @Operation(summary = "Predict the crowd level of a bus at a travel time using the ML model, and store it")
    public ResponseEntity<CrowdPredictionDto> predict(@Valid @RequestBody PredictionRequest request) {
        return new ResponseEntity<>(crowdPredictionService.predictForBus(request), HttpStatus.CREATED);
    }

    @GetMapping("/forecast/{routeId}")
    @Operation(summary = "Hourly crowd forecast for a route on a date, with the best hours to travel")
    public ResponseEntity<HourlyForecastDto> forecast(
            @PathVariable Long routeId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(defaultValue = "6") int fromHour,
            @RequestParam(defaultValue = "22") int toHour) {
        return ResponseEntity.ok(crowdPredictionService.forecastForRoute(routeId, date, fromHour, toHour));
    }

    @GetMapping("/alerts")
    @Operation(summary = "Buses predicted HIGH in the last hour")
    public ResponseEntity<List<CrowdPredictionDto>> alerts() {
        return ResponseEntity.ok(crowdPredictionService.recentHighCrowdAlerts());
    }

    @GetMapping("/model")
    @Operation(summary = "ML model status, type, dataset source and evaluation metrics")
    public ResponseEntity<ModelStatusDto> model() {
        return ResponseEntity.ok(dashboardService.getModelStatus());
    }
}
