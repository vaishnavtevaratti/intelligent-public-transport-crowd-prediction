package com.transport.backend.dto;

import com.transport.backend.entity.SensorHealth;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class ObservationDto {
    private Long id;
    private String sensorCode;
    private Long sequenceNumber;
    private LocalDateTime observedAt;
    private LocalDateTime receivedAt;
    private Integer visibleCount;
    private Integer entries;
    private Integer exits;
    private Integer occupiedSeats;
    private Double meanConfidence;
    private Double frameQuality;
    private SensorHealth health;
    private String sourceMode;
    private String status;
    private Integer previousOnboard;
    private Integer updatedOnboard;
    private String performedBy;
    private String note;
}
