package com.transport.backend.dto;

import com.transport.backend.entity.SensorHealth;
import com.transport.backend.entity.SensorType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class SensorDto {
    private Long id;
    private String sensorCode;
    private Long busId;
    private String busNumber;
    private SensorType type;
    private String description;
    private boolean enabled;
    private LocalDateTime createdAt;
    private LocalDateTime lastSeenAt;
    /** True when the sensor reported within app.sensors.offline-after-seconds. */
    private boolean online;
    private SensorHealth lastHealth;
    private String lastMessage;
    private String lastMode;
    private String detectorModel;
    private Long lastSequence;
    /** Returned ONCE when the sensor is created or its key is rotated; never stored in plain text. */
    private String apiKey;
}
