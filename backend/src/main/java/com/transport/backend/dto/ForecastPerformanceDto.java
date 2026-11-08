package com.transport.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Real-world check of live forecasts: each forecast whose target time has passed is compared with the
 * onboard estimate actually recorded at that time (within +/- 3 minutes).
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class ForecastPerformanceDto {
    private int forecastsChecked;
    private int forecastsWithoutActual;
    private Double meanAbsoluteError;
    private Double levelAccuracy;
    /** Same comparison for the persistence baseline (the onboard count at forecast time used as the prediction). */
    private Double persistenceMeanAbsoluteError;
    private String note;
}
