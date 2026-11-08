package com.transport.backend.entity;

public enum CrowdLevel {
    LOW,
    MEDIUM,
    HIGH,
    /** At or above total passenger capacity (live occupancy / 15-minute forecast only). */
    FULL
}
