package com.transport.backend;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Google Routes API integration, tested against a local fake server (Google is never contacted).
 * Verifies the request (endpoint, key header, field mask, waypoints in travel order) and the fallback.
 */
class RoutingIntegrationTest extends IntegrationTestBase {

    private static final HttpServer SERVER;
    private static final List<String> BODIES = new CopyOnWriteArrayList<>();
    private static final List<String> KEYS = new CopyOnWriteArrayList<>();
    private static final List<String> MASKS = new CopyOnWriteArrayList<>();
    private static final AtomicInteger FAIL = new AtomicInteger(0);

    static {
        try {
            SERVER = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        SERVER.createContext("/directions/v2:computeRoutes", exchange -> {
            BODIES.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            KEYS.add(exchange.getRequestHeaders().getFirst("X-Goog-Api-Key"));
            MASKS.add(exchange.getRequestHeaders().getFirst("X-Goog-FieldMask"));
            byte[] out = FAIL.get() > 0
                    ? "{\"error\":{\"code\":403,\"message\":\"API key not valid\"}}".getBytes(StandardCharsets.UTF_8)
                    : "{\"routes\":[{\"distanceMeters\":4321,\"duration\":\"610s\"}]}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(FAIL.get() > 0 ? 403 : 200, out.length);
            exchange.getResponseBody().write(out);
            exchange.close();
        });
        SERVER.start();
    }

    @DynamicPropertySource
    static void routing(DynamicPropertyRegistry registry) {
        registry.add("app.routing.google.api-key", () -> "test-server-key");
        registry.add("app.routing.google.base-url", () -> "http://127.0.0.1:" + SERVER.getAddress().getPort());
    }

    @AfterAll
    static void stop() {
        SERVER.stop(0);
    }

    private JsonNode optionFor(long routeId, String from, String to) throws Exception {
        JsonNode options = read(mvc.perform(auth(get("/api/travel/search?from=" + from + "&to=" + to), userToken))
                .andExpect(status().isOk()).andReturn());
        for (JsonNode o : options) {
            if (o.get("route").get("id").asLong() == routeId) return o;
        }
        throw new AssertionError("route not found");
    }

    @Test
    void roadDistanceComesFromRoutesApiAndFallsBackOnError() throws Exception {
        long routeId = createRoute();   // stops A (16.70,74.24) -> B (16.71,74.25) -> C (16.72,74.26)
        BODIES.clear();

        JsonNode option = optionFor(routeId, "Stop C", "Stop A");   // travel against stop order
        assertThat(option.get("routingSource").asText()).isEqualTo("GOOGLE_ROUTES");
        assertThat(option.get("distanceKm").asDouble()).isEqualTo(4.3);       // 4321 m
        assertThat(option.get("estimatedMinutes").asInt()).isEqualTo(12);     // 610 s -> 11 min, + 0.5 min dwell at B, rounded up
        assertThat(KEYS).contains("test-server-key");
        assertThat(MASKS).contains("routes.distanceMeters,routes.duration");
        String body = BODIES.get(BODIES.size() - 1);
        assertThat(body).contains("\"travelMode\":\"DRIVE\"");
        // origin = Stop C, intermediate = Stop B, destination = Stop A
        JsonNode request = json.readTree(body);
        assertThat(request.at("/origin/location/latLng/latitude").asDouble()).isEqualTo(16.72);
        assertThat(request.at("/intermediates/0/location/latLng/latitude").asDouble()).isEqualTo(16.71);
        assertThat(request.at("/destination/location/latLng/latitude").asDouble()).isEqualTo(16.70);

        int calls = BODIES.size();
        optionFor(routeId, "Stop C", "Stop A");
        assertThat(BODIES).hasSize(calls);   // second search served from the cache, no extra billed call

        FAIL.set(1);
        JsonNode fallback = optionFor(routeId, "Stop A", "Stop B"); // new pair, API now rejects the key
        assertThat(fallback.get("routingSource").asText()).isEqualTo("STRAIGHT_LINE_ESTIMATE");
        assertThat(fallback.get("distanceKm").asDouble()).isBetween(1.0, 2.0);
        FAIL.set(0);
    }
}
