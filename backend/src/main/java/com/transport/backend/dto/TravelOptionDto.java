package com.transport.backend.dto;

import com.transport.backend.entity.CrowdLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/** One way to travel between two stops on a route, with live buses and crowd information. */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class TravelOptionDto {

    private RouteDto route;
    private String fromStop;
    private String toStop;
    /** True when travelling in stop order (source -> destination). */
    private boolean forward;
    private int stopsBetween;
    /** Path length between the two stops; null if the route has no stop coordinates. */
    private Double distanceKm;
    /** Estimated in-bus travel time at the configured average speed; null if unknown. */
    private Integer estimatedMinutes;
    /**
     * GOOGLE_ROUTES (Routes API road distance and traffic-free time), ROAD_GEOMETRY_ESTIMATE (distance along the stored
     * OpenStreetMap road geometry at the average bus speed) or STRAIGHT_LINE_ESTIMATE (between stop coordinates).
     */
    private String routingSource;
    /** ML prediction for travelling now on this route (null if the ML service is unavailable). */
    private CrowdPredictionDto crowdNow;
    private List<Integer> bestHoursToday;
    private List<TravelBus> buses;
    private String note;

    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    public static class TravelBus {
        private Long busId;
        private String busNumber;
        private String status;
        private Integer capacity;
        private Double latitude;
        private Double longitude;
        private LocalDateTime lastUpdated;
        /** Estimated minutes until this bus reaches the boarding stop; null if unknown. */
        private Integer etaMinutes;
        private CrowdLevel latestCrowdLevel;
    }
}
