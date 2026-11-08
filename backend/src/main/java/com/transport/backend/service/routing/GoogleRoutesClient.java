package com.transport.backend.service.routing;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Optional road distance / drive time from the Google Routes API (computeRoutes).
 *
 * Enabled only when app.routing.google.api-key (env GOOGLE_ROUTES_API_KEY) is set. This must be a
 * SERVER-SIDE key restricted to the Routes API, never the browser Maps key. Results are cached for
 * 10 minutes per stop sequence to limit billed calls. On any error the caller falls back to the
 * straight-line estimate, so the travel planner never fails because of this service.
 *
 * Note: travel mode DRIVE on the road network is used as an approximation of a bus on the same roads
 * (TRANSIT mode does not accept intermediate stops and needs published timetables).
 */
@Component
public class GoogleRoutesClient {

    private static final Logger log = LoggerFactory.getLogger(GoogleRoutesClient.class);
    private static final Duration CACHE_TTL = Duration.ofMinutes(10);
    private static final int MAX_INTERMEDIATES = 25; // Routes API limit

    public record LatLng(double latitude, double longitude) {
    }

    public record RoadEstimate(double distanceKm, int durationMinutes) {
    }

    private record Cached(RoadEstimate value, Instant at) {
    }

    // Response shape: {"routes":[{"distanceMeters":1234,"duration":"345s"}]}
    record ComputeRoutesResponse(List<RouteResult> routes) {
    }

    record RouteResult(Long distanceMeters, String duration) {
    }

    private final String apiKey;
    private final RestClient restClient;
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();
    private volatile boolean warned;

    public GoogleRoutesClient(@Value("${app.routing.google.api-key:}") String apiKey,
                              @Value("${app.routing.google.base-url:https://routes.googleapis.com}") String baseUrl,
                              @Value("${app.routing.google.timeout-ms:4000}") int timeoutMs) {
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(timeoutMs);
        factory.setReadTimeout(timeoutMs);
        this.restClient = RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
    }

    public boolean isConfigured() {
        return !apiKey.isEmpty();
    }

    /** Road estimate through the given points (first = origin, last = destination), or empty. */
    public Optional<RoadEstimate> estimate(List<LatLng> points) {
        if (!isConfigured() || points.size() < 2 || points.size() - 2 > MAX_INTERMEDIATES) {
            return Optional.empty();
        }
        String key = points.toString();
        Cached cached = cache.get(key);
        if (cached != null && cached.at().isAfter(Instant.now().minus(CACHE_TTL))) {
            return Optional.of(cached.value());
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("origin", waypoint(points.get(0)));
        body.put("destination", waypoint(points.get(points.size() - 1)));
        if (points.size() > 2) {
            body.put("intermediates", points.subList(1, points.size() - 1).stream().map(GoogleRoutesClient::waypoint).toList());
        }
        body.put("travelMode", "DRIVE");
        try {
            ComputeRoutesResponse response = restClient.post()
                    .uri("/directions/v2:computeRoutes")
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("X-Goog-Api-Key", apiKey)
                    .header("X-Goog-FieldMask", "routes.distanceMeters,routes.duration")
                    .body(body)
                    .retrieve()
                    .body(ComputeRoutesResponse.class);
            if (response == null || response.routes() == null || response.routes().isEmpty()
                    || response.routes().get(0).distanceMeters() == null || response.routes().get(0).duration() == null) {
                log.warn("Google Routes API returned no route");
                return Optional.empty();
            }
            RouteResult r = response.routes().get(0);
            long seconds = Long.parseLong(r.duration().replace("s", "").split("\\.")[0]);
            RoadEstimate estimate = new RoadEstimate(Math.round(r.distanceMeters() / 100.0) / 10.0,
                    (int) Math.ceil(seconds / 60.0));
            cache.put(key, new Cached(estimate, Instant.now()));
            warned = false;
            return Optional.of(estimate);
        } catch (RestClientException | NumberFormatException ex) {
            if (!warned) { // log once per outage, never the key
                log.warn("Google Routes API unavailable, using straight-line estimates: {}", ex.getClass().getSimpleName());
                warned = true;
            }
            return Optional.empty();
        }
    }

    private static Map<String, Object> waypoint(LatLng p) {
        return Map.of("location", Map.of("latLng", Map.of("latitude", p.latitude(), "longitude", p.longitude())));
    }
}
