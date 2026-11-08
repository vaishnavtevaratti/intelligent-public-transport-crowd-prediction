package com.transport.backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

/** Start a passenger counter through the local counter agent (vision-service/agent.py). */
@Data
public class CounterStartRequest {

    @NotBlank(message = "Sensor code is required")
    @Pattern(regexp = "^[A-Za-z0-9][A-Za-z0-9_-]{2,39}$", message = "Sensor code must be 3-40 letters, digits, dashes or underscores")
    private String sensorCode;

    /** A camera index ("0" = laptop webcam) or a file from the agent's test_videos list. */
    @NotBlank(message = "Source is required")
    @Pattern(regexp = "^([0-9]|test_videos/[A-Za-z0-9._-]{1,80})$", message = "Source must be a camera index 0-9 or a test video")
    private String source;

    @NotBlank(message = "Mode is required")
    @Pattern(regexp = "^(door|cabin)$", message = "Mode must be door or cabin")
    private String mode;

    /** Must be true for a camera: everyone in view has agreed to be filmed. */
    private Boolean confirmConsent = false;

    /** Replay a test video in a loop (ignored for cameras). */
    private Boolean loop;
}
