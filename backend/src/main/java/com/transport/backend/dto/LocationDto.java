package com.transport.backend.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/** A bus position. Returned by REST and broadcast on the WebSocket topic /topic/location. */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class LocationDto {

    private Long id;

    @NotNull(message = "Bus ID is required")
    private Long busId;

    private String busNumber;

    private Long routeId;

    @NotNull(message = "Latitude is required")
    @DecimalMin(value = "-90.0", message = "Latitude must be between -90 and 90")
    @DecimalMax(value = "90.0", message = "Latitude must be between -90 and 90")
    private Double latitude;

    @NotNull(message = "Longitude is required")
    @DecimalMin(value = "-180.0", message = "Longitude must be between -180 and 180")
    @DecimalMax(value = "180.0", message = "Longitude must be between -180 and 180")
    private Double longitude;

    private LocalDateTime timestamp;

    private String status;

    /** SIMULATED or GPS. */
    private String source;

    private Double speedKmph;

    /** Degrees clockwise from north. */
    private Double heading;

    private String nextStop;

    /** Terminus the bus is heading to (simulator), e.g. "Tech Park". */
    private String direction;
}
