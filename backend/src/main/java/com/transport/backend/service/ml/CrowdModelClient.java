package com.transport.backend.service.ml;

import com.transport.backend.exception.ServiceUnavailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.util.List;
import java.util.Map;

/**
 * HTTP client for the Python crowd prediction service (ml-service/app.py).
 *
 * Request:  POST {base-url}/predict  {"instances":[{hour, day_of_week, route_distance_km, stop_count, bus_capacity}]}
 * Response: {"model_version": "...", "predictions":[{"crowd_level","confidence","probabilities":{LOW,MEDIUM,HIGH}}]}
 *
 * Any connection problem becomes a {@link ServiceUnavailableException} (HTTP 503 to the browser);
 * the backend never invents a prediction when the model cannot be reached.
 */
@Component
public class CrowdModelClient {

    private static final Logger log = LoggerFactory.getLogger(CrowdModelClient.class);

    private final RestClient restClient;
    private final String baseUrl;

    public CrowdModelClient(@Value("${app.ml.base-url}") String baseUrl,
                            @Value("${app.ml.timeout-ms:3000}") int timeoutMs) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(timeoutMs);
        factory.setReadTimeout(timeoutMs);
        this.baseUrl = baseUrl;
        this.restClient = RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
    }

    /** Input features for one prediction. dayOfWeek: 0 = Monday ... 6 = Sunday. */
    public record ModelInput(int hour, int dayOfWeek, Double routeDistanceKm, Integer stopCount, int busCapacity) {
        Map<String, Object> toJson() {
            java.util.HashMap<String, Object> map = new java.util.HashMap<>();
            map.put("hour", hour);
            map.put("day_of_week", dayOfWeek);
            map.put("route_distance_km", routeDistanceKm);
            map.put("stop_count", stopCount);
            map.put("bus_capacity", busCapacity);
            return map;
        }
    }

    /** outOfTrainingRange: inputs outside the data the model was trained on (the prediction is an extrapolation). */
    public record ModelOutput(String crowdLevel, double confidence, Map<String, Double> probabilities, String modelVersion,
                              List<String> outOfTrainingRange) {
        public boolean extrapolated() {
            return outOfTrainingRange != null && !outOfTrainingRange.isEmpty();
        }
    }

    record PredictResponse(String model_version, List<Prediction> predictions) {
    }

    record Prediction(String crowd_level, double confidence, Map<String, Double> probabilities, List<String> out_of_training_range) {
    }

    public List<ModelOutput> predict(List<ModelInput> inputs) {
        if (inputs.isEmpty()) {
            return List.of();
        }
        try {
            PredictResponse response = restClient.post()
                    .uri("/predict")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("instances", inputs.stream().map(ModelInput::toJson).toList()))
                    .retrieve()
                    .body(PredictResponse.class);
            if (response == null || response.predictions() == null || response.predictions().size() != inputs.size()) {
                throw new ServiceUnavailableException("Crowd prediction service returned an invalid response");
            }
            return response.predictions().stream()
                    .map(p -> new ModelOutput(p.crowd_level(), p.confidence(), p.probabilities(), response.model_version(),
                            p.out_of_training_range() != null ? p.out_of_training_range() : List.of()))
                    .toList();
        } catch (RestClientResponseException ex) {
            log.warn("ML service rejected request ({}): {}", ex.getStatusCode(), ex.getResponseBodyAsString());
            throw new ServiceUnavailableException("Crowd prediction service could not process the request", ex);
        } catch (RestClientException ex) {
            log.warn("ML service call failed: {}", ex.toString());
            throw new ServiceUnavailableException(
                    "Crowd prediction service is not reachable at " + baseUrl + ". Start it with: python ml-service/app.py", ex);
        }
    }

    public ModelOutput predictOne(ModelInput input) {
        return predict(List.of(input)).get(0);
    }

    /** Model outputs for read-only views, kept CACHE_TTL; the model is deterministic, so they equal a new call. */
    private static final java.time.Duration CACHE_TTL = java.time.Duration.ofMinutes(5);
    private static final int CACHE_LIMIT = 5000;
    private record CachedOutput(ModelOutput output, java.time.Instant expires) {
    }
    private final Map<ModelInput, CachedOutput> outputCache = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Like {@link #predict}, but serves inputs seen in the last 5 minutes from a cache and sends only the others to the
     * ML service. Used for read-only views (hourly forecast, travel planner), never for stored predictions, so a stored
     * prediction always comes from a fresh model call. The same input always gives the same output from the same model.
     */
    public List<ModelOutput> predictCached(List<ModelInput> inputs) {
        java.time.Instant now = java.time.Instant.now();
        List<ModelInput> missing = new java.util.ArrayList<>();
        for (ModelInput in : inputs) {
            CachedOutput c = outputCache.get(in);
            if (c == null || c.expires().isBefore(now)) {
                missing.add(in);
            }
        }
        if (!missing.isEmpty()) {
            List<ModelOutput> fresh = predict(missing.stream().distinct().toList());
            List<ModelInput> distinct = missing.stream().distinct().toList();
            if (outputCache.size() > CACHE_LIMIT) {
                outputCache.clear();
            }
            for (int i = 0; i < distinct.size(); i++) {
                outputCache.put(distinct.get(i), new CachedOutput(fresh.get(i), now.plus(CACHE_TTL)));
            }
        }
        return inputs.stream().map(in -> outputCache.get(in).output()).toList();
    }

    /** Inputs of the 15-minute live forecast. passengers15MinAgo may be null (no history). */
    public record ForecastInput(int minuteOfDay, int dayOfWeek, Double routeDistanceKm, Integer stopCount,
                                int busCapacity, int currentPassengers, Integer passengers15MinAgo) {
        Map<String, Object> toJson() {
            java.util.HashMap<String, Object> map = new java.util.HashMap<>();
            map.put("minute_of_day", minuteOfDay);
            map.put("day_of_week", dayOfWeek);
            map.put("route_distance_km", routeDistanceKm);
            map.put("stop_count", stopCount);
            map.put("bus_capacity", busCapacity);
            map.put("current_passengers", currentPassengers);
            map.put("passengers_15min_ago", passengers15MinAgo);
            return map;
        }
    }

    public record ForecastOutput(String crowdLevel, double confidence, Map<String, Double> probabilities,
                                 double predictedPassengers, int horizonMinutes, String modelVersion,
                                 List<String> outOfTrainingRange) {
        public boolean extrapolated() {
            return outOfTrainingRange != null && !outOfTrainingRange.isEmpty();
        }
    }

    record ForecastResponse(String model_version, List<ForecastPrediction> predictions) {
    }

    record ForecastPrediction(String crowd_level, double confidence, Map<String, Double> probabilities,
                              double predicted_passengers, int horizon_minutes, List<String> out_of_training_range) {
    }

    /** 15-minute-ahead forecast from live occupancy (ml-service POST /forecast). */
    public List<ForecastOutput> forecast(List<ForecastInput> inputs) {
        if (inputs.isEmpty()) {
            return List.of();
        }
        try {
            ForecastResponse response = restClient.post()
                    .uri("/forecast")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("instances", inputs.stream().map(ForecastInput::toJson).toList()))
                    .retrieve()
                    .body(ForecastResponse.class);
            if (response == null || response.predictions() == null || response.predictions().size() != inputs.size()) {
                throw new ServiceUnavailableException("Forecast service returned an invalid response");
            }
            return response.predictions().stream()
                    .map(p -> new ForecastOutput(p.crowd_level(), p.confidence(), p.probabilities(),
                            p.predicted_passengers(), p.horizon_minutes(), response.model_version(),
                            p.out_of_training_range() != null ? p.out_of_training_range() : List.of()))
                    .toList();
        } catch (RestClientResponseException ex) {
            log.warn("ML forecast rejected request ({}): {}", ex.getStatusCode(), ex.getResponseBodyAsString());
            throw new ServiceUnavailableException("Live forecast model could not process the request", ex);
        } catch (RestClientException ex) {
            log.warn("ML forecast call failed: {}", ex.toString());
            throw new ServiceUnavailableException("Crowd prediction service is not reachable at " + baseUrl, ex);
        }
    }

    /** Metrics of the live forecast model (ml-service GET /forecast/info). */
    public Map<String, Object> forecastInfo() {
        try {
            Map<String, Object> info = restClient.get().uri("/forecast/info").retrieve()
                    .body(new ParameterizedTypeReference<Map<String, Object>>() { });
            if (info == null) {
                throw new ServiceUnavailableException("Forecast service returned no model information");
            }
            return info;
        } catch (RestClientException ex) {
            throw new ServiceUnavailableException("Live forecast model is not available at " + baseUrl, ex);
        }
    }

    /** Returns the model metadata document, or throws ServiceUnavailableException. */
    public Map<String, Object> modelInfo() {
        try {
            Map<String, Object> info = restClient.get().uri("/model/info").retrieve()
                    .body(new ParameterizedTypeReference<Map<String, Object>>() { });
            if (info == null) {
                throw new ServiceUnavailableException("Crowd prediction service returned no model information");
            }
            return info;
        } catch (RestClientException ex) {
            throw new ServiceUnavailableException("Crowd prediction service is not reachable at " + baseUrl, ex);
        }
    }

    private volatile long availabilityCheckedAt;
    private volatile boolean lastAvailability;

    /** Whether the ML service answers its health check; the answer is reused for 5 seconds. */
    public boolean isAvailable() {
        long now = System.currentTimeMillis();
        if (now - availabilityCheckedAt < 5000) {
            return lastAvailability;
        }
        boolean up;
        try {
            restClient.get().uri("/health").retrieve().toBodilessEntity();
            up = true;
        } catch (RestClientException ex) {
            up = false;
        }
        lastAvailability = up;
        availabilityCheckedAt = now;
        return up;
    }
}
