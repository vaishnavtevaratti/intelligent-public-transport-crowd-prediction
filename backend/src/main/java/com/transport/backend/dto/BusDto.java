package com.transport.backend.dto;

import com.transport.backend.entity.CrowdLevel;
import jakarta.validation.constraints.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class BusDto {

    private Long id;

    /** Demo label of the assigned route, e.g. "D1" (read-only). */
    private String routeCode;

    @NotBlank(message = "Bus number is required")
    @Pattern(regexp = "^[A-Za-z0-9][A-Za-z0-9 -]{1,19}$", message = "Bus number must be 2-20 letters, digits, spaces or dashes")
    private String busNumber;

    @NotNull(message = "Capacity is required")
    @Min(value = 1, message = "Capacity must be at least 1")
    @Max(value = 200, message = "Capacity cannot exceed 200")
    private Integer capacity;

    /** Number of seats (optional). Must not exceed the total passenger capacity. */
    @Min(value = 1, message = "Seat count must be at least 1")
    @Max(value = 200, message = "Seat count cannot exceed 200")
    private Integer seatCount;

    @DecimalMin(value = "-90.0", message = "Latitude must be between -90 and 90")
    @DecimalMax(value = "90.0", message = "Latitude must be between -90 and 90")
    private Double currentLatitude;

    @DecimalMin(value = "-180.0", message = "Longitude must be between -180 and 180")
    @DecimalMax(value = "180.0", message = "Longitude must be between -180 and 180")
    private Double currentLongitude;

    @Pattern(regexp = "ACTIVE|INACTIVE|MAINTENANCE", message = "Status must be ACTIVE, INACTIVE or MAINTENANCE")
    private String status;

    private Long routeId;

    private String routeSource;

    private String routeDestination;

    private LocalDateTime lastUpdated;

    /** Latest stored ML prediction for this bus (read-only). */
    private CrowdLevel latestCrowdLevel;

    private LocalDateTime latestCrowdTime;

    /** Simulator state (read-only; null when the bus is not being simulated). */
    private String nextStop;
    private String direction;
    private Double speedKmph;
}
