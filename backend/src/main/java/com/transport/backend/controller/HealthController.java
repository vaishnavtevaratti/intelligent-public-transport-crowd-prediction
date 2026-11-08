package com.transport.backend.controller;

import com.transport.backend.service.ml.CrowdModelClient;
import com.transport.backend.service.occupancy.PassengerSimulationService;
import com.transport.backend.service.occupancy.VisionAgentClient;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Public health check for start-up scripts and monitoring. Says only whether each part answers
 * (no versions, hosts or credentials). 200 when the backend and database work, 503 otherwise.
 */
@RestController
@Tag(name = "Health", description = "Service health (public)")
public class HealthController {

    private final JdbcTemplate jdbc;
    private final CrowdModelClient modelClient;
    private final VisionAgentClient agentClient;
    private final PassengerSimulationService passengerSimulation;
    private final boolean gpsSimulation;

    public HealthController(JdbcTemplate jdbc, CrowdModelClient modelClient, VisionAgentClient agentClient,
                            PassengerSimulationService passengerSimulation,
                            @Value("${app.simulation.enabled:true}") boolean gpsSimulation) {
        this.jdbc = jdbc;
        this.modelClient = modelClient;
        this.agentClient = agentClient;
        this.passengerSimulation = passengerSimulation;
        this.gpsSimulation = gpsSimulation;
    }

    @GetMapping("/api/health")
    @Operation(summary = "Backend, database, ML service and counter agent status; tracking mode")
    public ResponseEntity<Map<String, Object>> health() {
        boolean database;
        try {
            database = Integer.valueOf(1).equals(jdbc.queryForObject("SELECT 1", Integer.class));
        } catch (RuntimeException e) {
            database = false;
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", database ? "UP" : "DOWN");
        body.put("database", database ? "UP" : "DOWN");
        body.put("mlService", modelClient.isAvailable() ? "UP" : "DOWN");
        body.put("counterAgent", agentClient.isUp() ? "UP" : "NOT_RUNNING");   // optional component
        body.put("trackingMode", gpsSimulation ? "SIMULATED_GPS" : "REAL_GPS_ONLY");
        body.put("passengerSimulation", passengerSimulation.isEnabled() ? "ON (buses without sensors)" : "OFF");
        body.put("time", LocalDateTime.now().withNano(0).toString());
        return ResponseEntity.status(database ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE).body(body);
    }
}
