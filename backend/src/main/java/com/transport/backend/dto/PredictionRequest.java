package com.transport.backend.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class PredictionRequest {

    @NotNull(message = "Bus is required")
    private Long busId;

    /** Planned travel time; defaults to now. */
    private LocalDateTime travelTime;
}
