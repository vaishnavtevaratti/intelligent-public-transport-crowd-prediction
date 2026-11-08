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
public class AdminAnalyticsDto {

    private long totalUsers;
    private long adminUsers;
    private long totalBuses;
    private long totalRoutes;
    private long totalPredictions;
    private long locationRecords;
    private Map<String, Long> busesByStatus;
    /** Predictions made in the last 24 hours, per level. */
    private Map<String, Long> predictionsLast24h;
    /** Predictions of the last 7 days per hour of day: [{hour, LOW, MEDIUM, HIGH}]. */
    private List<Map<String, Object>> predictionsByHour;
    /** Current crowd per route from each bus's latest prediction: [{routeId, label, LOW, MEDIUM, HIGH}]. */
    private List<Map<String, Object>> routeCrowd;
    private boolean simulationEnabled;
    private ModelStatusDto model;
}
