package com.transport.backend.service.occupancy;

/**
 * The occupancy formulas, kept free of Spring/JPA so they are easy to test and explain.
 * Capacity (seats + standing) and seat count are separate inputs; a passenger count is never
 * treated as an occupied-seat count.
 */
public final class OccupancyMath {

    private OccupancyMath() {
    }

    /** max(0, total passenger capacity - estimated onboard); null when either is unknown. */
    public static Integer availableCapacity(Integer capacity, Integer onboard) {
        if (capacity == null || onboard == null) return null;
        return Math.max(0, capacity - onboard);
    }

    /** max(0, seats - occupied seats); null unless BOTH are known (occupied seats must be measured). */
    public static Integer availableSeats(Integer seatCount, Integer occupiedSeats) {
        if (seatCount == null || occupiedSeats == null) return null;
        return Math.max(0, seatCount - occupiedSeats);
    }

    /** max(0, onboard - occupied seats); null unless occupied seats are measured. */
    public static Integer standingPassengers(Integer onboard, Integer occupiedSeats) {
        if (onboard == null || occupiedSeats == null) return null;
        return Math.max(0, onboard - occupiedSeats);
    }

    /** onboard / capacity as a percentage (may exceed 100 when overcrowded); null when unknown. */
    public static Double occupancyPercent(Integer capacity, Integer onboard) {
        if (capacity == null || capacity <= 0 || onboard == null) return null;
        return Math.round(onboard * 1000.0 / capacity) / 10.0;
    }

    public static boolean overCapacity(Integer capacity, Integer onboard) {
        return capacity != null && onboard != null && onboard > capacity;
    }
}
