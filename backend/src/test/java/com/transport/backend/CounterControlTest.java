package com.transport.backend;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Start/stop of passenger counters through the backend, against a local fake counter agent
 * (no process or camera is started). Checks admin-only access, validation, token forwarding
 * and how agent errors are passed on.
 */
class CounterControlTest extends IntegrationTestBase {

    private static final HttpServer SERVER;
    private static final List<String> TOKENS = new CopyOnWriteArrayList<>();
    private static final List<String> BODIES = new CopyOnWriteArrayList<>();
    private static final List<String> POST_LENGTHS = new CopyOnWriteArrayList<>();

    static {
        try {
            SERVER = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        SERVER.createContext("/counters", exchange -> {
            TOKENS.add(exchange.getRequestHeaders().getFirst("X-Agent-Token"));
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            BODIES.add(body);
            if ("POST".equals(exchange.getRequestMethod())) {
                POST_LENGTHS.add(String.valueOf(exchange.getRequestHeaders().getFirst("Content-Length")));
            }
            String path = exchange.getRequestURI().getPath();
            int code = 200;
            String out;
            if (path.equals("/counters/CAM-2/frame.jpg")) {   // detector still starting: no frame yet
                exchange.sendResponseHeaders(204, -1);
                exchange.close();
                return;
            }
            if (path.equals("/counters/CAM-1/frame.jpg")) {
                byte[] jpeg = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xD9};
                exchange.getResponseHeaders().add("Content-Type", "image/jpeg");
                exchange.sendResponseHeaders(200, jpeg.length);
                exchange.getResponseBody().write(jpeg);
                exchange.close();
                return;
            }
            if (path.equals("/counters/CAM-1/live")) {
                out = "{\"visible\":2,\"sessionEntries\":3,\"sessionExits\":1,\"health\":\"OK\"}";
            } else if (path.equals("/counters/CAM-1/reset")) {
                out = "{\"reset\":true}";
            } else if (path.equals("/counters")) {
                out = "{\"counters\":[{\"sensorCode\":\"CAM-1\",\"state\":\"STOPPED\"}],\"testVideos\":[\"test_videos/a.avi\"]}";
            } else if (path.equals("/counters/start") && body.contains("BUSY")) {
                code = 409;
                out = "{\"error\":\"a counter for BUSY is already running\"}";
            } else if (path.equals("/counters/start")) {
                out = "{\"sensorCode\":\"CAM-1\",\"state\":\"RUNNING\"}";
            } else {
                out = "{\"sensorCode\":\"CAM-1\",\"state\":\"STOPPING\"}";
            }
            byte[] bytes = out.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(code, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        SERVER.createContext("/health", exchange -> {
            byte[] bytes = "{\"status\":\"UP\",\"running\":0}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        SERVER.start();
    }

    @DynamicPropertySource
    static void agent(DynamicPropertyRegistry registry) {
        registry.add("app.vision-agent.base-url", () -> "http://127.0.0.1:" + SERVER.getAddress().getPort());
        registry.add("app.vision-agent.token", () -> "test-agent-token");
    }

    @AfterAll
    static void stop() {
        SERVER.stop(0);
    }

    private String start(String sensor, String source, boolean consent) {
        return "{\"sensorCode\":\"" + sensor + "\",\"source\":\"" + source + "\",\"mode\":\"cabin\",\"confirmConsent\":" + consent + "}";
    }

    @Test
    void adminStartsListsAndStopsCountersThroughTheAgent() throws Exception {
        mvc.perform(auth(get("/api/admin/counters"), adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.testVideos[0]").value("test_videos/a.avi"));
        mvc.perform(auth(post("/api/admin/counters/start"), adminToken).contentType(MediaType.APPLICATION_JSON)
                        .content(start("cam-1", "0", true)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("RUNNING"));
        assertThat(BODIES.get(BODIES.size() - 1)).contains("\"sensorCode\":\"CAM-1\"").contains("\"confirmConsent\":true");
        mvc.perform(auth(post("/api/admin/counters/cam-1/stop"), adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("STOPPING"));
        assertThat(TOKENS).isNotEmpty().allMatch("test-agent-token"::equals);
        // the Python agent needs a Content-Length (a chunked body arrived empty before this was fixed)
        assertThat(POST_LENGTHS).isNotEmpty().allMatch(l -> l.matches("[0-9]+") && !l.equals("0"));
    }

    @Test
    void liveViewNumbersFrameAndResetAreProxiedForAdminsOnly() throws Exception {
        mvc.perform(auth(get("/api/admin/counters/cam-1/live"), adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.visible").value(2))
                .andExpect(jsonPath("$.sessionEntries").value(3));
        byte[] frame = mvc.perform(auth(get("/api/admin/counters/cam-1/frame"), adminToken))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "image/jpeg"))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andReturn().getResponse().getContentAsByteArray();
        assertThat(frame).hasSize(4).startsWith((byte) 0xFF, (byte) 0xD8);
        mvc.perform(auth(get("/api/admin/counters/cam-2/frame"), adminToken)).andExpect(status().isNoContent());
        mvc.perform(auth(post("/api/admin/counters/cam-1/reset"), adminToken))
                .andExpect(status().isOk()).andExpect(jsonPath("$.reset").value(true));
        mvc.perform(auth(get("/api/admin/counters/cam-1/frame"), userToken)).andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/counters/cam-1/live")).andExpect(status().isUnauthorized());
    }

    @Test
    void publicHealthReportsEachPartWithoutDetails() throws Exception {
        mvc.perform(get("/api/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.database").value("UP"))
                .andExpect(jsonPath("$.mlService").value("DOWN"))     // not running in tests
                .andExpect(jsonPath("$.counterAgent").value("UP"))    // the fake agent
                .andExpect(jsonPath("$.trackingMode").exists());
    }

    @Test
    void onlyAdminsMayControlCounters() throws Exception {
        mvc.perform(auth(get("/api/admin/counters"), userToken)).andExpect(status().isForbidden());
        mvc.perform(post("/api/admin/counters/start").contentType(MediaType.APPLICATION_JSON)
                .content(start("CAM-1", "0", true))).andExpect(status().isUnauthorized());
    }

    @Test
    void invalidSourcesAreRejectedAndAgentConflictsArePassedOn() throws Exception {
        int before = BODIES.size();
        mvc.perform(auth(post("/api/admin/counters/start"), adminToken).contentType(MediaType.APPLICATION_JSON)
                .content(start("CAM-1", "../../secret.txt", false))).andExpect(status().isBadRequest());
        assertThat(BODIES).hasSize(before);   // never forwarded
        mvc.perform(auth(post("/api/admin/counters/start"), adminToken).contentType(MediaType.APPLICATION_JSON)
                        .content(start("BUSY", "test_videos/a.avi", false)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("a counter for BUSY is already running"));
    }
}
