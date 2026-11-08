package com.transport.backend.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class RouteDto {

    private Long id;

    @NotBlank(message = "Source is required")
    @Size(max = 120, message = "Source is too long")
    private String source;

    @NotBlank(message = "Destination is required")
    @Size(max = 120, message = "Destination is too long")
    private String destination;

    /** Route length in km. If omitted it is calculated from the stop coordinates. */
    @Positive(message = "Distance must be greater than 0")
    @Max(value = 500, message = "Distance cannot exceed 500 km")
    private Double distance;

    @Size(max = 255, message = "Description is too long")
    private String description;

    /** Ordered stops (first = source, last = destination). Optional, but needed for the map and simulation. */
    @Valid
    @Size(max = 60, message = "A route can have at most 60 stops")
    @Builder.Default
    private List<RouteStopDto> stops = new ArrayList<>();

    /** Number of buses assigned (read-only). */
    private Long busCount;

    /** Short demo label such as "D1" (read-only; not an official route number). */
    private String routeCode;

    /** True for fictional academic demo routes (read-only). */
    private boolean demoRoute;

    /** Road geometry [[lat, lng], ...] when known (read-only); null = only straight lines between stops. */
    private List<double[]> pathGeometry;

    /** Source of pathGeometry, e.g. "OSRM / OpenStreetMap road network" (read-only). */
    private String pathSource;
}
