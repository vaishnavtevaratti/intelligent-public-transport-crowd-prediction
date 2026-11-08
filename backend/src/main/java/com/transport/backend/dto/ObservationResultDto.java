package com.transport.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Response to a device report: how it was processed and the resulting occupancy. */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class ObservationResultDto {
    private Long observationId;
    /** APPLIED, APPLIED_LATE, CLAMPED_NEGATIVE, SNAPSHOT, HEALTH_ONLY or DUPLICATE_IGNORED. */
    private String status;
    private OccupancyDto occupancy;
}
