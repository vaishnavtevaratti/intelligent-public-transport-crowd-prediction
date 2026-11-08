package com.transport.backend.dto;

import com.transport.backend.entity.CrowdLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.Map;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class CrowdPredictionDto {

    private Long id;
    private Long busId;
    private String busNumber;
    private Integer busCapacity;
    private Long routeId;
    private String routeSource;
    private String routeDestination;
    private CrowdLevel crowdLevel;
    /** When the prediction was made. */
    private LocalDateTime timestamp;
    /** Travel time the prediction applies to. */
    private LocalDateTime predictedFor;
    private Double confidence;
    private Map<String, Double> probabilities;
    private String modelVersion;
    /** ON_DEMAND, SCHEDULED, or null for legacy records made before the ML model existed. */
    private String source;
    /** TIME_ROUTE (time and route only) or LIVE_FORECAST (uses the live onboard estimate). */
    private String modelKind;
    private Integer horizonMinutes;
    private Double predictedPassengers;
    private Integer inputOnboard;
}
