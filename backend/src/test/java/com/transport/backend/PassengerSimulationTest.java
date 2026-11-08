package com.transport.backend;

import com.fasterxml.jackson.databind.JsonNode;
import com.transport.backend.service.occupancy.PassengerSimulationService;
import com.transport.backend.service.occupancy.PassengerSimulationServiceAccess;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** SIMULATED passenger counts: only for buses without sensors, always 0..capacity, never over real data. */
class PassengerSimulationTest extends IntegrationTestBase {

    @Autowired
    private PassengerSimulationService simulation;

    private JsonNode occupancy(long busId) throws Exception {
        return read(mvc.perform(auth(get("/api/occupancy/" + busId), userToken)).andExpect(status().isOk()).andReturn());
    }

    private static String sensorCode() {
        String code = unique("SIM").replace("-", "");
        return code.substring(0, Math.min(12, code.length()));
    }

    @Test
    void busesWithoutSensorGetLabelledSimulatedCountsWithinCapacity() throws Exception {
        long routeId = createRoute();
        long plain = createBus(routeId);
        long withSensor = createBus(routeId);
        long manual = createBus(routeId);
        mvc.perform(auth(post("/api/admin/sensors"), adminToken).content(toJson(Map.of(
                "sensorCode", sensorCode(), "busId", withSensor, "type", "DOOR_CAMERA"))))
                .andExpect(status().isCreated());
        mvc.perform(auth(put("/api/occupancy/" + manual + "/reconcile"), adminToken)
                .content(toJson(Map.of("onboard", 7, "note", "head count")))).andExpect(status().isOk());

        for (int i = 0; i < 30; i++) {
            simulation.tick();
            JsonNode o = occupancy(plain);
            assertThat(o.get("dataMode").asText()).isEqualTo("SIMULATED");
            assertThat(o.get("method").asText()).isEqualTo("SIMULATED");
            assertThat(o.get("estimatedOnboard").asInt()).isBetween(0, 40);   // capacity of the test bus
            assertThat(o.get("confidence").isNull()).isTrue();                  // no fake confidence
            assertThat(o.get("availableSeats").isNull()).isTrue();              // seats are never simulated
        }
        assertThat(occupancy(withSensor).get("dataStatus").asText()).isEqualTo("NO_DATA");   // waits for its sensor
        assertThat(occupancy(manual).get("estimatedOnboard").asInt()).isEqualTo(7);          // manual count kept
        assertThat(occupancy(manual).get("dataMode").asText()).isEqualTo("MANUAL");
    }

    @Test
    void demandFollowsPeaksAndCountsAreClamped() {
        LocalDateTime weekdayPeak = LocalDateTime.of(2026, 10, 26, 8, 30);    // Monday 08:30
        LocalDateTime weekdayNight = LocalDateTime.of(2026, 10, 26, 2, 0);
        LocalDateTime sundayPeak = LocalDateTime.of(2026, 10, 25, 8, 30);
        assertThat(PassengerSimulationServiceAccess.demand(weekdayPeak, 1L)).isGreaterThan(PassengerSimulationServiceAccess.demand(weekdayNight, 1L));
        assertThat(PassengerSimulationServiceAccess.demand(sundayPeak, 1L)).isLessThan(PassengerSimulationServiceAccess.demand(weekdayPeak, 1L));
        assertThat(PassengerSimulationServiceAccess.next(48, 50, 1.05, 40)).isEqualTo(50);    // never above capacity
        assertThat(PassengerSimulationServiceAccess.next(1, 50, 0.0, -40)).isZero();          // never negative
    }
}
