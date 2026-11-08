package com.transport.backend.controller;

import com.transport.backend.dto.CounterStartRequest;
import com.transport.backend.service.occupancy.VisionAgentClient;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * Start / stop passenger counters from the web app (ROLE_ADMIN enforced for /api/admin/**).
 * The backend only forwards to the local counter agent; it never receives camera images.
 */
@RestController
@RequestMapping("/api/admin/counters")
@Tag(name = "Administration", description = "User management and system analytics (ADMIN only)")
public class CounterController {

    private static final Logger log = LoggerFactory.getLogger(CounterController.class);

    private final VisionAgentClient agent;

    public CounterController(VisionAgentClient agent) {
        this.agent = agent;
    }

    @GetMapping
    @Operation(summary = "State of each counter on the local counter agent, plus the available test videos")
    public ResponseEntity<Map<String, Object>> list() {
        return ResponseEntity.ok(agent.list());
    }

    @PostMapping("/start")
    @Operation(summary = "Start a counter on a camera (consent required) or a test video")
    public ResponseEntity<Map<String, Object>> start(@Valid @RequestBody CounterStartRequest request, Authentication auth) {
        Map<String, Object> body = new HashMap<>();
        body.put("sensorCode", request.getSensorCode().toUpperCase());
        body.put("source", request.getSource());
        body.put("mode", request.getMode());
        body.put("confirmConsent", Boolean.TRUE.equals(request.getConfirmConsent()));
        if (request.getLoop() != null) {
            body.put("loop", request.getLoop());
        }
        log.info("Counter start requested by {}: sensor {} source {} mode {}", auth.getName(), body.get("sensorCode"),
                request.getSource(), request.getMode());
        return ResponseEntity.ok(agent.start(body));
    }

    @GetMapping("/{sensorCode}/live")
    @Operation(summary = "Live numbers of a running counter: people visible now, crossings this session, fps, health")
    public ResponseEntity<Map<String, Object>> live(@PathVariable String sensorCode) {
        return ResponseEntity.ok(agent.live(sensorCode.toUpperCase()));
    }

    @GetMapping(value = "/{sensorCode}/frame", produces = MediaType.IMAGE_JPEG_VALUE)
    @Operation(summary = "Latest annotated frame of a running counter (JPEG; 204 while the detector is starting; never stored)")
    public ResponseEntity<byte[]> frame(@PathVariable String sensorCode) {
        byte[] jpeg = agent.frame(sensorCode.toUpperCase());
        if (jpeg == null || jpeg.length == 0) {
            return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
        }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).contentType(MediaType.IMAGE_JPEG).body(jpeg);
    }

    @PostMapping("/{sensorCode}/reset")
    @Operation(summary = "Reset the session in/out totals shown in the live view (the onboard estimate is not changed)")
    public ResponseEntity<Map<String, Object>> reset(@PathVariable String sensorCode, Authentication auth) {
        log.info("Counter session reset requested by {}: sensor {}", auth.getName(), sensorCode);
        return ResponseEntity.ok(agent.resetSession(sensorCode.toUpperCase()));
    }

    @PostMapping("/{sensorCode}/stop")
    @Operation(summary = "Stop a running counter (graceful; the camera is released)")
    public ResponseEntity<Map<String, Object>> stop(@PathVariable String sensorCode, Authentication auth) {
        log.info("Counter stop requested by {}: sensor {}", auth.getName(), sensorCode);
        return ResponseEntity.ok(agent.stop(sensorCode.toUpperCase()));
    }
}
