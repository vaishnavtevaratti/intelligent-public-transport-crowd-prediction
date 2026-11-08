package com.transport.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class DashboardDto {

    private long totalBuses;
    private long totalRoutes;
    private long activeBuses;
    private long totalStops;
    /** Current crowd level of each bus (its latest prediction), counted per level. */
    private Map<String, Long> crowdSummary;
    private List<CrowdPredictionDto> recentPredictions;
    /** HIGH predictions from the last hour. */
    private List<CrowdPredictionDto> highCrowdAlerts;
    private boolean mlServiceAvailable;
    private boolean simulationEnabled;
}
