package com.transport.backend.dto;

import com.transport.backend.entity.CrowdLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Live occupancy of one bus. Values derived from sensors are ESTIMATES; null means unknown.
 *   availableCapacity = max(0, capacity - estimatedOnboard)
 *   availableSeats    = max(0, seatCount - occupiedSeats)        (only when occupied seats are measured)
 *   standing          = max(0, estimatedOnboard - occupiedSeats) (only when occupied seats are measured)
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class OccupancyDto {
    private Long busId;
    private String busNumber;
    private Long routeId;
    /** Total passenger capacity (seats + standing). */
    private Integer capacity;
    /** Number of seats; null = not configured. */
    private Integer seatCount;

    /** NO_SENSOR, NO_DATA, LIVE or STALE. */
    private String dataStatus;
    /** DOOR_COUNT, CABIN_VISIBLE (lower bound) or MANUAL. */
    private String method;
    /** CAMERA, TEST_VIDEO, SEAT_SENSOR or MANUAL. */
    private String dataMode;
    private Integer estimatedOnboard;
    private boolean onboardIsLowerBound;
    private Double confidence;
    private Integer availableCapacity;
    private Double occupancyPercent;
    private CrowdLevel crowdCategory;
    private boolean overCapacity;

    private Integer occupiedSeats;
    private Integer availableSeats;
    private Integer standingPassengers;
    /** MEASURED (seat sensor), MANUAL (counted by staff) or UNKNOWN. */
    private String seatDataStatus;
    private LocalDateTime seatUpdatedAt;

    private Integer lastVisibleCount;
    private LocalDateTime lastVisibleAt;
    private LocalDateTime updatedAt;
    private boolean needsReconciliation;
    private String reconciliationReason;
    private List<SensorDto> sensors;

    /** Latest 15-minute live forecast made from the current data, if any. */
    private CrowdPredictionDto liveForecast;
}
