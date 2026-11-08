package com.transport.backend.service.occupancy;

import java.time.LocalDateTime;

/** Test access to the package-private calculations of PassengerSimulationService. */
public final class PassengerSimulationServiceAccess {

    private PassengerSimulationServiceAccess() {
    }

    public static double demand(LocalDateTime at, Long routeId) {
        return PassengerSimulationService.demand(at, routeId);
    }

    public static int next(Integer current, int capacity, double targetFraction, double noise) {
        return PassengerSimulationService.nextOnboard(current, capacity, targetFraction, noise);
    }
}
