package com.transport.backend.service.occupancy;

import com.transport.backend.entity.CrowdLevel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Crowd category from the occupancy ratio (estimated onboard / total passenger capacity).
 * Configurable in application.properties; defaults match the ML forecast model's training labels.
 */
@Component
public class CrowdThresholds {

    private final double medium;
    private final double high;
    private final double full;

    public CrowdThresholds(@Value("${app.crowd.thresholds.medium:0.50}") double medium,
                           @Value("${app.crowd.thresholds.high:0.85}") double high,
                           @Value("${app.crowd.thresholds.full:1.00}") double full) {
        if (!(0 < medium && medium < high && high < full)) {
            throw new IllegalStateException("Crowd thresholds must satisfy 0 < medium < high < full");
        }
        this.medium = medium;
        this.high = high;
        this.full = full;
    }

    public CrowdLevel categorize(double occupancyRatio) {
        if (occupancyRatio >= full) return CrowdLevel.FULL;
        if (occupancyRatio >= high) return CrowdLevel.HIGH;
        if (occupancyRatio >= medium) return CrowdLevel.MEDIUM;
        return CrowdLevel.LOW;
    }

    public double getMedium() { return medium; }
    public double getHigh() { return high; }
    public double getFull() { return full; }
}
