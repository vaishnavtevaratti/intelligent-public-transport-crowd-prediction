package com.transport.backend.entity;

public enum SensorType {
    /** Doorway-facing camera: counts entries and exits by line crossing. */
    DOOR_CAMERA,
    /** Cabin camera: counts people visible in its view (a lower bound of passengers onboard). */
    CABIN_CAMERA,
    /** Seat occupancy sensors: report the number of occupied seats. */
    SEAT_SENSOR
}
