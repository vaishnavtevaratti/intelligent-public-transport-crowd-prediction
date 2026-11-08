package com.transport.backend;

import com.fasterxml.jackson.databind.JsonNode;
import com.transport.backend.service.ml.CrowdModelClient.ForecastOutput;
import com.transport.backend.service.occupancy.OccupancyMath;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class OccupancyTest extends IntegrationTestBase {

    // ------------------------------------------------------------------ helpers

    private long createBus(int capacity, Integer seats) throws Exception {
        Map<String, Object> body = new HashMap<>();
        String number = unique("OC-");
        body.put("busNumber", number.substring(0, Math.min(18, number.length())));
        body.put("capacity", capacity);
        body.put("seatCount", seats);
        body.put("routeId", createRoute());
        return read(mvc.perform(auth(post("/api/buses"), adminToken).content(toJson(body)))
                .andExpect(status().isCreated()).andReturn()).get("id").asLong();
    }

    /** Registers a sensor and returns {code, key}. */
    private String[] sensor(long busId, String type) throws Exception {
        String code = unique("CAM-").replace(" ", "");
        JsonNode res = read(mvc.perform(auth(post("/api/admin/sensors"), adminToken)
                        .content(toJson(Map.of("sensorCode", code, "busId", busId, "type", type))))
                .andExpect(status().isCreated()).andReturn());
        assertThat(res.get("apiKey").asText()).hasSizeGreaterThan(30);
        return new String[]{res.get("sensorCode").asText(), res.get("apiKey").asText()};
    }

    private ResultActions report(String[] sensor, Map<String, Object> fields) throws Exception {
        Map<String, Object> body = new HashMap<>(Map.of("observedAt", LocalDateTime.now().minusSeconds(5).toString(),
                "health", "OK", "mode", "TEST_VIDEO", "meanConfidence", 0.8, "frameQuality", 0.9));
        body.putAll(fields);
        return mvc.perform(post("/api/sensors/observations").contentType(MediaType.APPLICATION_JSON)
                .header("X-Sensor-Code", sensor[0]).header("X-Sensor-Key", sensor[1]).content(toJson(body)));
    }

    private JsonNode door(String[] sensor, long seq, int entries, int exits) throws Exception {
        return read(report(sensor, Map.of("sequenceNumber", seq, "entries", entries, "exits", exits))
                .andExpect(status().isOk()).andReturn());
    }

    private JsonNode occupancy(long busId) throws Exception {
        return read(mvc.perform(auth(get("/api/occupancy/" + busId), userToken)).andExpect(status().isOk()).andReturn());
    }

    // ------------------------------------------------------------------ formulas

    @Test
    void formulasKeepCapacityAndSeatsSeparate() {
        // capacity 50 (seats + standing), 40 seats, 46 onboard, 38 seats measured as occupied
        assertThat(OccupancyMath.availableCapacity(50, 46)).isEqualTo(4);
        assertThat(OccupancyMath.availableSeats(40, 38)).isEqualTo(2);
        assertThat(OccupancyMath.standingPassengers(46, 38)).isEqualTo(8);
        assertThat(OccupancyMath.availableCapacity(50, 60)).isZero();          // never negative
        assertThat(OccupancyMath.occupancyPercent(50, 60)).isEqualTo(120.0);
        assertThat(OccupancyMath.overCapacity(50, 60)).isTrue();
        // seats are UNKNOWN unless occupied seats were measured: no number is invented from the passenger count
        assertThat(OccupancyMath.availableSeats(40, null)).isNull();
        assertThat(OccupancyMath.standingPassengers(46, null)).isNull();
    }

    @Test
    void seatCountCannotExceedCapacity() throws Exception {
        Map<String, Object> body = Map.of("busNumber", "SEAT-X1", "capacity", 30, "seatCount", 40);
        mvc.perform(auth(post("/api/buses"), adminToken).content(toJson(body)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("cannot exceed")));
    }

    // ------------------------------------------------------------------ sensor auth

    @Test
    void sensorKeyIsShownOnceAndChecked() throws Exception {
        long bus = createBus(50, 40);
        String[] s = sensor(bus, "DOOR_CAMERA");
        JsonNode list = read(mvc.perform(auth(get("/api/admin/sensors"), adminToken)).andReturn());
        for (JsonNode n : list) {
            assertThat(n.get("apiKey").isNull()).isTrue();
        }
        report(new String[]{s[0], "wrong-key"}, Map.of("sequenceNumber", 1, "entries", 1, "exits", 0))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/sensors/observations").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(auth(get("/api/admin/sensors"), userToken)).andExpect(status().isForbidden());
        // invalid payloads are rejected
        report(s, Map.of("sequenceNumber", 1, "entries", -3, "exits", 0)).andExpect(status().isBadRequest());
        report(s, Map.of("sequenceNumber", 1, "entries", 1, "exits", 0,
                "observedAt", LocalDateTime.now().plusHours(1).toString())).andExpect(status().isBadRequest());
    }

    // ------------------------------------------------------------------ door reconciliation

    @Test
    void doorCountsReconcileDuplicatesGapsLateReportsAndNegatives() throws Exception {
        long bus = createBus(50, 40);
        String[] door = sensor(bus, "DOOR_CAMERA");

        JsonNode r1 = door(door, 1, 5, 0);
        assertThat(r1.get("status").asText()).isEqualTo("APPLIED");
        assertThat(r1.get("occupancy").get("estimatedOnboard").asInt()).isEqualTo(5);
        // nobody told us how many were onboard before counting started
        assertThat(r1.get("occupancy").get("needsReconciliation").asBoolean()).isTrue();

        assertThat(door(door, 2, 3, 1).get("occupancy").get("estimatedOnboard").asInt()).isEqualTo(7);

        JsonNode dup = door(door, 2, 3, 1);
        assertThat(dup.get("status").asText()).isEqualTo("DUPLICATE_IGNORED");
        assertThat(dup.get("occupancy").get("estimatedOnboard").asInt()).isEqualTo(7);

        JsonNode gap = door(door, 4, 2, 0); // report 3 is missing
        assertThat(gap.get("occupancy").get("estimatedOnboard").asInt()).isEqualTo(9);
        assertThat(gap.get("occupancy").get("reconciliationReason").asText()).contains("missing");

        JsonNode late = door(door, 3, 1, 0); // the missing report arrives late
        assertThat(late.get("status").asText()).isEqualTo("APPLIED_LATE");
        assertThat(late.get("occupancy").get("estimatedOnboard").asInt()).isEqualTo(10);

        JsonNode negative = door(door, 5, 0, 25);
        assertThat(negative.get("status").asText()).isEqualTo("CLAMPED_NEGATIVE");
        assertThat(negative.get("occupancy").get("estimatedOnboard").asInt()).isZero();

        JsonNode log = read(mvc.perform(auth(get("/api/occupancy/" + bus + "/observations"), adminToken)).andReturn());
        assertThat(log.get("totalElements").asInt()).isEqualTo(5); // duplicate not stored twice
        mvc.perform(auth(get("/api/occupancy/" + bus + "/observations"), userToken)).andExpect(status().isForbidden());
    }

    @Test
    void manualReconciliationSetsCountsAndSeatAvailability() throws Exception {
        long bus = createBus(50, 40);
        String[] door = sensor(bus, "DOOR_CAMERA");
        door(door, 1, 30, 0);

        Map<String, Object> body = new HashMap<>(Map.of("onboard", 46, "occupiedSeats", 38, "note", "Conductor head count"));
        mvc.perform(auth(put("/api/occupancy/" + bus + "/reconcile"), userToken).content(toJson(body))).andExpect(status().isForbidden());
        JsonNode dto = read(mvc.perform(auth(put("/api/occupancy/" + bus + "/reconcile"), adminToken).content(toJson(body)))
                .andExpect(status().isOk()).andReturn());
        assertThat(dto.get("method").asText()).isEqualTo("MANUAL");
        assertThat(dto.get("needsReconciliation").asBoolean()).isFalse();
        assertThat(dto.get("availableCapacity").asInt()).isEqualTo(4);
        assertThat(dto.get("availableSeats").asInt()).isEqualTo(2);
        assertThat(dto.get("standingPassengers").asInt()).isEqualTo(8);
        assertThat(dto.get("seatDataStatus").asText()).isEqualTo("MANUAL");
        assertThat(dto.get("crowdCategory").asText()).isEqualTo("HIGH"); // 92 %

        // later door counts continue from the reconciled value
        assertThat(door(door, 2, 6, 0).get("occupancy").get("estimatedOnboard").asInt()).isEqualTo(52);
        JsonNode full = occupancy(bus);
        assertThat(full.get("crowdCategory").asText()).isEqualTo("FULL");
        assertThat(full.get("overCapacity").asBoolean()).isTrue();

        body.put("occupiedSeats", 47); // more than the 40 seats
        mvc.perform(auth(put("/api/occupancy/" + bus + "/reconcile"), adminToken).content(toJson(body))).andExpect(status().isBadRequest());
        body.put("occupiedSeats", 30);
        body.put("onboard", 20); // fewer passengers than occupied seats
        mvc.perform(auth(put("/api/occupancy/" + bus + "/reconcile"), adminToken).content(toJson(body))).andExpect(status().isBadRequest());
    }

    @Test
    void cabinCameraGivesLowerBoundAndSeatsStayUnknown() throws Exception {
        long bus = createBus(60, null);
        String[] cabin = sensor(bus, "CABIN_CAMERA");
        assertThat(occupancy(bus).get("dataStatus").asText()).isEqualTo("NO_DATA");

        JsonNode r = read(report(cabin, Map.of("sequenceNumber", 1, "visibleCount", 12)).andExpect(status().isOk()).andReturn());
        JsonNode dto = r.get("occupancy");
        assertThat(dto.get("estimatedOnboard").asInt()).isEqualTo(12);
        assertThat(dto.get("onboardIsLowerBound").asBoolean()).isTrue();
        assertThat(dto.get("method").asText()).isEqualTo("CABIN_VISIBLE");
        assertThat(dto.get("dataMode").asText()).isEqualTo("TEST_VIDEO");
        assertThat(dto.get("availableCapacity").asInt()).isEqualTo(48);
        assertThat(dto.get("seatDataStatus").asText()).isEqualTo("UNKNOWN");
        assertThat(dto.get("availableSeats").isNull()).isTrue();

        // a bus with no sensors at all
        long plain = createBus(40, 30);
        assertThat(occupancy(plain).get("dataStatus").asText()).isEqualTo("NO_SENSOR");
        assertThat(occupancy(plain).get("estimatedOnboard").isNull()).isTrue();
    }

    @Test
    void seatSensorAndCameraFailuresAreValidated() throws Exception {
        long bus = createBus(50, 40);
        String[] seats = sensor(bus, "SEAT_SENSOR");
        report(seats, Map.of("sequenceNumber", 1, "occupiedSeats", 45, "mode", "SEAT_SENSOR")).andExpect(status().isBadRequest());
        report(seats, Map.of("sequenceNumber", 2, "occupiedSeats", 33, "mode", "SEAT_SENSOR")).andExpect(status().isOk());
        JsonNode dto = occupancy(bus);
        assertThat(dto.get("occupiedSeats").asInt()).isEqualTo(33);
        assertThat(dto.get("seatDataStatus").asText()).isEqualTo("MEASURED");

        String[] door = sensor(bus, "DOOR_CAMERA");
        JsonNode down = read(report(door, Map.of("sequenceNumber", 1, "health", "CAMERA_UNAVAILABLE", "message", "cannot open camera"))
                .andExpect(status().isOk()).andReturn());
        assertThat(down.get("status").asText()).isEqualTo("HEALTH_ONLY");
        assertThat(down.get("occupancy").get("reconciliationReason").asText()).contains("CAMERA_UNAVAILABLE");
        JsonNode sensors = read(mvc.perform(auth(get("/api/admin/sensors"), adminToken)).andReturn());
        boolean found = false;
        for (JsonNode n : sensors) {
            if (n.get("sensorCode").asText().equals(door[0])) {
                found = true;
                assertThat(n.get("lastHealth").asText()).isEqualTo("CAMERA_UNAVAILABLE");
                assertThat(n.get("online").asBoolean()).isTrue();
            }
        }
        assertThat(found).isTrue();
    }

    @Test
    void webcamDemoReportsAreAcceptedAndLabelledAsSuch() throws Exception {
        long bus = createBus(4, 2);
        String[] cabin = sensor(bus, "CABIN_CAMERA");
        JsonNode r = read(report(cabin, Map.of("sequenceNumber", 1, "visibleCount", 2, "mode", "WEBCAM_DEMO"))
                .andExpect(status().isOk()).andReturn());
        JsonNode dto = r.get("occupancy");
        assertThat(dto.get("dataMode").asText()).isEqualTo("WEBCAM_DEMO");   // never shown as a bus camera
        assertThat(dto.get("estimatedOnboard").asInt()).isEqualTo(2);
        assertThat(dto.get("occupancyPercent").asDouble()).isEqualTo(50.0);
        assertThat(dto.get("crowdCategory").asText()).isEqualTo("MEDIUM");
        report(cabin, Map.of("sequenceNumber", 2, "visibleCount", 1, "mode", "PHONE")).andExpect(status().isBadRequest());
    }

    @Test
    void liveForecastIsStoredWithHorizonAfterAReport() throws Exception {
        when(modelClient.forecast(anyList())).thenReturn(List.of(new ForecastOutput("HIGH", 0.7,
                Map.of("LOW", 0.05, "MEDIUM", 0.15, "HIGH", 0.7, "FULL", 0.1), 44.5, 15, "forecast-test-1", List.of())));
        long bus = createBus(50, 40);
        String[] door = sensor(bus, "DOOR_CAMERA");
        JsonNode r = door(door, 1, 40, 0);
        JsonNode forecast = r.get("occupancy").get("liveForecast");
        assertThat(forecast.get("modelKind").asText()).isEqualTo("LIVE_FORECAST");
        assertThat(forecast.get("horizonMinutes").asInt()).isEqualTo(15);
        assertThat(forecast.get("predictedPassengers").asDouble()).isEqualTo(44.5);
        assertThat(forecast.get("inputOnboard").asInt()).isEqualTo(40);
        assertThat(forecast.get("probabilities").get("FULL").asDouble()).isEqualTo(0.1);

        JsonNode history = read(mvc.perform(auth(get("/api/crowd/history?busId=" + bus), userToken)).andReturn());
        assertThat(history.get("content").get(0).get("modelKind").asText()).isEqualTo("LIVE_FORECAST");
    }
}
