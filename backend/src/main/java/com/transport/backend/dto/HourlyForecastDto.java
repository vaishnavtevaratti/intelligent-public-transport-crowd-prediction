package com.transport.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** Crowd forecast for every service hour of a day on one route, plus the suggested best travel time. */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class HourlyForecastDto {

    private Long routeId;
    private String routeLabel;
    private LocalDate date;
    private int busCapacityUsed;
    private String modelVersion;
    private List<HourForecast> hours;
    /** Hours (within the requested window) with the lowest expected crowding, best first. */
    private List<Integer> bestHours;
    /** Stored predictions for this route in the last 30 days, grouped by hour (SRS travel suggestion algorithm). */
    private List<HistoricalHour> history;

    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    public static class HourForecast {
        private int hour;
        private String crowdLevel;
        private double confidence;
        private Map<String, Double> probabilities;
        /** 0 = certainly LOW ... 1 = certainly HIGH (P(MEDIUM) * 0.5 + P(HIGH)). */
        private double crowdScore;
    }

    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    public static class HistoricalHour {
        private int hour;
        private long low;
        private long medium;
        private long high;
    }
}
