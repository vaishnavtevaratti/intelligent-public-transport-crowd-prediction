package com.transport.backend.dto;

import com.transport.backend.entity.SensorHealth;
import jakarta.validation.constraints.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * A report from a camera/sensor device (vision-service/run_counter.py).
 * entries/exits are counts SINCE THE PREVIOUS REPORT of the same sensor.
 */
@Data
public class ObservationRequest {

    /** Increases by 1 with every report of this sensor; used to drop duplicates and detect gaps. */
    @NotNull(message = "sequenceNumber is required")
    @PositiveOrZero(message = "sequenceNumber must be >= 0")
    private Long sequenceNumber;

    @NotNull(message = "observedAt is required")
    private LocalDateTime observedAt;

    @Min(value = 0, message = "visibleCount must be >= 0")
    @Max(value = 300, message = "visibleCount must be <= 300")
    private Integer visibleCount;

    @Min(value = 0, message = "entries must be >= 0")
    @Max(value = 300, message = "entries must be <= 300")
    private Integer entries;

    @Min(value = 0, message = "exits must be >= 0")
    @Max(value = 300, message = "exits must be <= 300")
    private Integer exits;

    @Min(value = 0, message = "occupiedSeats must be >= 0")
    @Max(value = 200, message = "occupiedSeats must be <= 200")
    private Integer occupiedSeats;

    @DecimalMin(value = "0.0", message = "meanConfidence must be between 0 and 1")
    @DecimalMax(value = "1.0", message = "meanConfidence must be between 0 and 1")
    private Double meanConfidence;

    @DecimalMin(value = "0.0", message = "frameQuality must be between 0 and 1")
    @DecimalMax(value = "1.0", message = "frameQuality must be between 0 and 1")
    private Double frameQuality;

    @NotNull(message = "health is required")
    private SensorHealth health;

    /**
     * CAMERA (camera installed in a bus), WEBCAM_DEMO (laptop webcam demonstration, not a bus camera),
     * TEST_VIDEO (recorded video replay) or SEAT_SENSOR.
     */
    @NotBlank(message = "mode is required")
    @Pattern(regexp = "CAMERA|WEBCAM_DEMO|TEST_VIDEO|SEAT_SENSOR", message = "mode must be CAMERA, WEBCAM_DEMO, TEST_VIDEO or SEAT_SENSOR")
    private String mode;

    @Size(max = 60, message = "detectorModel is too long")
    private String detectorModel;

    @Size(max = 200, message = "message is too long")
    private String message;
}
