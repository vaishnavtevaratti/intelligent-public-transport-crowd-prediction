package com.transport.backend;

import com.fasterxml.jackson.databind.JsonNode;
import com.transport.backend.entity.CrowdLevel;
import com.transport.backend.exception.ServiceUnavailableException;
import com.transport.backend.service.ml.CrowdModelClient.ModelInput;
import com.transport.backend.service.ml.CrowdModelClient.ModelOutput;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class CrowdPredictionTest extends IntegrationTestBase {

    private static ModelOutput output(String level) {
        Map<String, Double> p = Map.of("LOW", level.equals("LOW") ? 0.8 : 0.1,
                "MEDIUM", level.equals("MEDIUM") ? 0.8 : 0.1, "HIGH", level.equals("HIGH") ? 0.8 : 0.1);
        return new ModelOutput(level, 0.8, p, "test-model-1", List.of());
    }

    @org.springframework.beans.factory.annotation.Autowired
    private com.transport.backend.repository.CrowdPredictionRepository predictionRepository;
    @org.springframework.beans.factory.annotation.Autowired
    private com.transport.backend.repository.BusRepository busRepository;

    private void storePrediction(long busId, CrowdLevel level, java.time.LocalDateTime at) {
        var bus = busRepository.findById(busId).orElseThrow();
        predictionRepository.save(com.transport.backend.entity.CrowdPrediction.builder()
                .bus(bus).route(bus.getRoute()).crowdLevel(level).timestamp(at).confidence(0.7)
                .modelKind("TIME_ROUTE").source("SCHEDULED").modelVersion("test").build());
    }

    @Test
    void busListShowsTheNewestPredictionOfEachBus() throws Exception {
        long routeId = createRoute();
        long a = createBus(routeId);
        long b = createBus(routeId);
        java.time.LocalDateTime now = java.time.LocalDateTime.now().withNano(0);
        storePrediction(a, CrowdLevel.LOW, now.minusHours(3));
        storePrediction(a, CrowdLevel.HIGH, now.minusHours(1));      // newest of bus a
        storePrediction(a, CrowdLevel.MEDIUM, now.minusHours(2));
        storePrediction(b, CrowdLevel.MEDIUM, now.minusMinutes(10)); // newest of bus b
        storePrediction(b, CrowdLevel.HIGH, now.minusHours(5));

        JsonNode buses = read(mvc.perform(auth(get("/api/buses"), userToken)).andExpect(status().isOk()).andReturn());
        Map<Long, String> latest = new java.util.HashMap<>();
        buses.forEach(n -> latest.put(n.get("id").asLong(), n.path("latestCrowdLevel").asText(null)));
        assertThat(latest.get(a)).isEqualTo("HIGH");
        assertThat(latest.get(b)).isEqualTo("MEDIUM");
    }

    @Test
    void predictionUsesModelWithRouteFeaturesAndIsStored() throws Exception {
        long busId = createBus(createRoute());
        when(modelClient.predictOne(any())).thenReturn(output("HIGH"));
        String nextTuesdayMorning = java.time.LocalDate.now()
                .with(java.time.temporal.TemporalAdjusters.next(java.time.DayOfWeek.TUESDAY)).atTime(8, 30).toString();

        mvc.perform(auth(post("/api/crowd/predict"), userToken)
                        .content(toJson(Map.of("busId", busId, "travelTime", nextTuesdayMorning))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.crowdLevel").value("HIGH"))
                .andExpect(jsonPath("$.confidence").value(0.8))
                .andExpect(jsonPath("$.modelVersion").value("test-model-1"))
                .andExpect(jsonPath("$.source").value("ON_DEMAND"));

        ArgumentCaptor<ModelInput> input = ArgumentCaptor.forClass(ModelInput.class);
        verify(modelClient).predictOne(input.capture());
        assertThat(input.getValue().hour()).isEqualTo(8);
        assertThat(input.getValue().dayOfWeek()).isEqualTo(1); // Tuesday, with Monday = 0
        assertThat(input.getValue().stopCount()).isEqualTo(3);
        assertThat(input.getValue().busCapacity()).isEqualTo(40);

        JsonNode history = read(mvc.perform(auth(get("/api/crowd/history?busId=" + busId), userToken))
                .andExpect(status().isOk()).andReturn());
        assertThat(history.get("totalElements").asInt()).isEqualTo(1);
        assertThat(history.get("content").get(0).get("probabilities").get("HIGH").asDouble()).isEqualTo(0.8);
    }

    @Test
    void modelOutageReturns503AndStoresNothing() throws Exception {
        long busId = createBus(createRoute());
        when(modelClient.predictOne(any())).thenThrow(new ServiceUnavailableException("Crowd prediction service is not reachable"));

        mvc.perform(auth(post("/api/crowd/predict"), userToken).content(toJson(Map.of("busId", busId))))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.message").value("Crowd prediction service is not reachable"));

        JsonNode history = read(mvc.perform(auth(get("/api/crowd/history?busId=" + busId), userToken)).andReturn());
        assertThat(history.get("totalElements").asInt()).isZero();
    }

    @Test
    void predictionOutsideTrainingRangeIsRefusedAndNotStored() throws Exception {
        long busId = createBus(createRoute());
        when(modelClient.predictOne(any())).thenReturn(new ModelOutput("LOW", 0.9,
                Map.of("LOW", 0.9, "MEDIUM", 0.05, "HIGH", 0.05), "test-model-1", List.of("bus_capacity")));
        mvc.perform(auth(post("/api/crowd/predict"), userToken).content(toJson(Map.of("busId", busId))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("not trained")));
        JsonNode history = read(mvc.perform(auth(get("/api/crowd/history?busId=" + busId), userToken)).andReturn());
        assertThat(history.get("totalElements").asInt()).isZero();
    }

    @Test
    void invalidPredictionRequestsAreRejected() throws Exception {
        long busWithoutRoute = createBus(null);
        mvc.perform(auth(post("/api/crowd/predict"), userToken).content(toJson(Map.of("busId", busWithoutRoute))))
                .andExpect(status().isBadRequest());
        mvc.perform(auth(post("/api/crowd/predict"), userToken).content(toJson(Map.of())))
                .andExpect(status().isBadRequest());
        mvc.perform(auth(post("/api/crowd/predict"), userToken).content(toJson(Map.of("busId", 987654))))
                .andExpect(status().isNotFound());
        long busId = createBus(createRoute());
        mvc.perform(auth(post("/api/crowd/predict"), userToken)
                        .content(toJson(Map.of("busId", busId, "travelTime", "2030-01-01T10:00:00"))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void forecastSuggestsLeastCrowdedHours() throws Exception {
        long routeId = createRoute();
        when(modelClient.predictCached(anyList())).thenAnswer(inv -> {
            List<ModelInput> inputs = inv.getArgument(0);
            List<ModelOutput> outs = new ArrayList<>();
            for (ModelInput in : inputs) {
                int h = in.hour();
                outs.add(output(h == 8 || h == 18 ? "HIGH" : (h == 11 || h == 14 ? "LOW" : "MEDIUM")));
            }
            return outs;
        });

        JsonNode forecast = read(mvc.perform(auth(get("/api/crowd/forecast/" + routeId + "?date=2026-12-01&fromHour=7&toHour=19"), userToken))
                .andExpect(status().isOk()).andReturn());
        assertThat(forecast.get("hours")).hasSize(24);
        assertThat(forecast.get("bestHours").get(0).asInt()).isEqualTo(11);
        assertThat(forecast.get("bestHours").get(1).asInt()).isEqualTo(14);

        mvc.perform(auth(get("/api/crowd/forecast/" + routeId + "?fromHour=20&toHour=5"), userToken))
                .andExpect(status().isBadRequest());
    }

    @Test
    void dashboardReflectsStoredData() throws Exception {
        when(modelClient.isAvailable()).thenReturn(false);
        mvc.perform(auth(get("/api/dashboard"), userToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mlServiceAvailable").value(false))
                .andExpect(jsonPath("$.crowdSummary.LOW").exists());
        mvc.perform(auth(get("/api/admin/analytics"), adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.predictionsByHour.length()").value(24));
    }
}
