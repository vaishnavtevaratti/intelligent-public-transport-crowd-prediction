package com.transport.backend;

import com.fasterxml.jackson.databind.JsonNode;
import com.transport.backend.repository.LocationRepository;
import com.transport.backend.service.BusSimulationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class BusRouteManagementTest extends IntegrationTestBase {

    @Autowired
    BusSimulationService simulationService;
    @Autowired
    LocationRepository locationRepository;

    @Test
    void routeWithStopsGetsDistanceCalculatedAndIsReadable() throws Exception {
        long routeId = createRoute();
        JsonNode route = read(mvc.perform(auth(get("/api/routes/" + routeId), userToken))
                .andExpect(status().isOk()).andReturn());
        assertThat(route.get("stops")).hasSize(3);
        assertThat(route.get("distance").asDouble()).isBetween(2.5, 3.5); // ~3 km between A and C
    }

    @Test
    void invalidRouteIsRejected() throws Exception {
        Map<String, Object> body = new HashMap<>(Map.of("source", "Same", "destination", "same", "distance", 5));
        mvc.perform(auth(post("/api/routes"), adminToken).content(toJson(body))).andExpect(status().isBadRequest());

        body = new HashMap<>(Map.of("source", "A", "destination", "B",
                "stops", List.of(Map.of("name", "Only", "latitude", 1, "longitude", 1))));
        mvc.perform(auth(post("/api/routes"), adminToken).content(toJson(body))).andExpect(status().isBadRequest());

        body = new HashMap<>(Map.of("source", "A", "destination", "B",
                "stops", List.of(Map.of("name", "X", "latitude", 95, "longitude", 1), Map.of("name", "Y", "latitude", 1, "longitude", 1))));
        mvc.perform(auth(post("/api/routes"), adminToken).content(toJson(body))).andExpect(status().isBadRequest());
    }

    @Test
    void busCrudPersistsAndValidates() throws Exception {
        long routeId = createRoute();
        long busId = createBus(routeId);

        JsonNode bus = read(mvc.perform(auth(get("/api/buses/" + busId), userToken)).andExpect(status().isOk()).andReturn());
        assertThat(bus.get("routeId").asLong()).isEqualTo(routeId);
        assertThat(bus.get("currentLatitude").asDouble()).isEqualTo(16.70); // starts at first stop

        Map<String, Object> update = new HashMap<>();
        update.put("busNumber", bus.get("busNumber").asText());
        update.put("capacity", 60);
        update.put("status", "MAINTENANCE");
        update.put("routeId", routeId);
        mvc.perform(auth(put("/api/buses/" + busId), adminToken).content(toJson(update)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.capacity").value(60))
                .andExpect(jsonPath("$.status").value("MAINTENANCE"));

        // duplicate number (case-insensitive)
        Map<String, Object> duplicate = Map.of("busNumber", bus.get("busNumber").asText().toLowerCase(), "capacity", 10);
        mvc.perform(auth(post("/api/buses"), adminToken).content(toJson(duplicate))).andExpect(status().isConflict());
        // invalid capacity and status
        mvc.perform(auth(post("/api/buses"), adminToken).content(toJson(Map.of("busNumber", "OK-1", "capacity", 0))))
                .andExpect(status().isBadRequest());
        mvc.perform(auth(post("/api/buses"), adminToken).content(toJson(Map.of("busNumber", "OK-2", "capacity", 5, "status", "FLYING"))))
                .andExpect(status().isBadRequest());
        // unknown route
        mvc.perform(auth(post("/api/buses"), adminToken).content(toJson(Map.of("busNumber", "OK-3", "capacity", 5, "routeId", 999999))))
                .andExpect(status().isNotFound());
    }

    @Test
    void routeWithBusesCannotBeDeletedButCanAfterBusIsRemoved() throws Exception {
        long routeId = createRoute();
        long busId = createBus(routeId);

        mvc.perform(auth(delete("/api/routes/" + routeId), adminToken))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("assigned bus")));

        mvc.perform(auth(delete("/api/buses/" + busId), adminToken)).andExpect(status().isNoContent());
        mvc.perform(auth(get("/api/buses/" + busId), adminToken)).andExpect(status().isNotFound());
        mvc.perform(auth(delete("/api/routes/" + routeId), adminToken)).andExpect(status().isNoContent());
    }

    @Test
    void simulatorMovesActiveBusAlongRouteAndStoresHistory() throws Exception {
        long routeId = createRoute();
        long busId = createBus(routeId);
        long before = locationRepository.countByBusId(busId);

        simulationService.tick();
        simulationService.tick();

        JsonNode bus = read(mvc.perform(auth(get("/api/buses/" + busId), userToken)).andReturn());
        double lat = bus.get("currentLatitude").asDouble();
        double lng = bus.get("currentLongitude").asDouble();
        assertThat(lat).isBetween(16.70, 16.72);           // still on the route between A and C
        assertThat(lng).isBetween(74.24, 74.26);
        assertThat(lat).isNotEqualTo(16.70);               // and it moved away from the first stop
        assertThat(bus.get("lastUpdated").isNull()).isFalse();
        // the REST response already carries the simulator state, before any WebSocket message
        assertThat(bus.get("nextStop").asText()).isIn("Stop A", "Stop B", "Stop C");
        assertThat(bus.get("direction").asText()).isIn("Stop A", "Stop C");
        assertThat(bus.get("speedKmph").isNull()).isFalse();
        assertThat(locationRepository.countByBusId(busId)).isEqualTo(before + 2);

        JsonNode history = read(mvc.perform(auth(get("/api/location/history/" + busId), userToken)).andReturn());
        assertThat(history.get(0).get("source").asText()).isEqualTo("SIMULATED");
    }

    @Autowired
    com.transport.backend.scheduler.BusLocationScheduler scheduler;
    @Autowired
    com.transport.backend.repository.BusRepository busRepository;

    @Test
    void retentionCleanupDeletesOnlyOldLocationHistory() throws Exception {
        long busId = createBus(createRoute());
        var bus = busRepository.findById(busId).orElseThrow();
        locationRepository.save(com.transport.backend.entity.Location.builder().bus(bus).latitude(1.0).longitude(1.0)
                .timestamp(java.time.LocalDateTime.now().minusHours(30)).source("SIMULATED").build());
        locationRepository.save(com.transport.backend.entity.Location.builder().bus(bus).latitude(1.0).longitude(1.0)
                .timestamp(java.time.LocalDateTime.now().minusMinutes(5)).source("SIMULATED").build());

        scheduler.cleanUpOldData();

        assertThat(locationRepository.countByBusId(busId)).isEqualTo(1);
    }

    @Test
    void realGpsUpdateIsStoredAsGps() throws Exception {
        long busId = createBus(createRoute());
        mvc.perform(auth(post("/api/location/update"), adminToken)
                        .content(toJson(Map.of("busId", busId, "latitude", 16.7111, "longitude", 74.2511))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.source").value("GPS"));
        // the simulator leaves a bus with a fresh GPS fix alone
        simulationService.tick();
        JsonNode bus = read(mvc.perform(auth(get("/api/buses/" + busId), userToken)).andReturn());
        assertThat(bus.get("currentLatitude").asDouble()).isEqualTo(16.7111);
    }

    @Test
    void travelSearchFindsRouteWithDistanceAndEta() throws Exception {
        long routeId = createRoute();
        createBus(routeId);
        JsonNode options = read(mvc.perform(auth(get("/api/travel/search?from=Stop A&to=Stop C"), userToken))
                .andExpect(status().isOk()).andReturn());
        JsonNode option = null;
        for (JsonNode o : options) {
            if (o.get("route").get("id").asLong() == routeId) option = o;
        }
        assertThat(option).isNotNull();
        assertThat(option.get("forward").asBoolean()).isTrue();
        assertThat(option.get("stopsBetween").asInt()).isEqualTo(1);
        assertThat(option.get("estimatedMinutes").asInt()).isPositive();
        assertThat(option.get("buses").get(0).get("etaMinutes").isNull()).isFalse();
        // ML service is mocked as unavailable here, so the response explains it instead of inventing data
        assertThat(option.get("crowdNow").isNull()).isTrue();
        assertThat(option.get("note").asText()).contains("unavailable");

        mvc.perform(auth(get("/api/travel/search?from=Stop A&to=Stop A"), userToken)).andExpect(status().isBadRequest());
    }
}
