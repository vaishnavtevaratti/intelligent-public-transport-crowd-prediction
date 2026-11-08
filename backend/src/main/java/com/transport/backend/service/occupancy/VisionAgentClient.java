package com.transport.backend.service.occupancy;

import com.transport.backend.exception.BadRequestException;
import com.transport.backend.exception.ConflictException;
import com.transport.backend.exception.ServiceUnavailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.http.client.BufferingClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * HTTP client for the local counter agent (vision-service/agent.py) that starts and stops run_counter.py.
 *
 * The agent only listens on 127.0.0.1 and requires the X-Agent-Token header. The token comes from
 * app.vision-agent.token (VISION_AGENT_TOKEN) or, if empty, from the agent's token file, which is re-read
 * on every call so the agent may be started after the backend.
 *
 * Agent 400/409 answers are passed on with the agent's message; an unreachable agent becomes a 503.
 */
@Component
public class VisionAgentClient {

    private static final Logger log = LoggerFactory.getLogger(VisionAgentClient.class);
    private static final ParameterizedTypeReference<Map<String, Object>> JSON = new ParameterizedTypeReference<>() {
    };

    private final RestClient restClient;
    private final String configuredToken;
    private final Path tokenFile;

    public VisionAgentClient(@Value("${app.vision-agent.base-url:http://127.0.0.1:8002}") String baseUrl,
                             @Value("${app.vision-agent.token:}") String configuredToken,
                             @Value("${app.vision-agent.token-file:../vision-service/agent_token.txt}") String tokenFile,
                             @Value("${app.vision-agent.timeout-ms:5000}") int timeoutMs) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(timeoutMs);
        factory.setReadTimeout(timeoutMs);
        // buffered so the JSON body is sent with a Content-Length instead of chunked
        this.restClient = RestClient.builder().baseUrl(baseUrl).requestFactory(new BufferingClientHttpRequestFactory(factory)).build();
        this.configuredToken = configuredToken;
        this.tokenFile = Path.of(tokenFile);
    }

    public Map<String, Object> list() {
        return call(() -> restClient.get().uri("/counters").header("X-Agent-Token", token()).retrieve().body(JSON));
    }

    public Map<String, Object> start(Map<String, Object> body) {
        return post("/counters/start", body);
    }

    public Map<String, Object> stop(String sensorCode) {
        return post("/counters/stop", Map.of("sensorCode", sensorCode));
    }

    /** Live numbers of a running counter (people visible now, crossings this session, fps, health). */
    public Map<String, Object> live(String sensorCode) {
        return call(() -> restClient.get().uri("/counters/{code}/live", sensorCode).header("X-Agent-Token", token()).retrieve().body(JSON));
    }

    /** Latest annotated frame (JPEG) of a running counter. Held in memory only; never stored by the backend. */
    public byte[] frame(String sensorCode) {
        return call(() -> restClient.get().uri("/counters/{code}/frame.jpg", sensorCode).header("X-Agent-Token", token())
                .retrieve().body(byte[].class));
    }

    public Map<String, Object> resetSession(String sensorCode) {
        return call(() -> restClient.post().uri("/counters/{code}/reset", sensorCode).header("X-Agent-Token", token())
                .contentType(MediaType.APPLICATION_JSON).body(Map.of()).retrieve().body(JSON));
    }

    /** True when the agent answers its health check (no token needed). */
    public boolean isUp() {
        try {
            restClient.get().uri("/health").retrieve().toBodilessEntity();
            return true;
        } catch (RestClientException e) {
            return false;
        }
    }

    private Map<String, Object> post(String path, Map<String, Object> body) {
        return call(() -> restClient.post().uri(path).header("X-Agent-Token", token())
                .contentType(MediaType.APPLICATION_JSON).body(body).retrieve().body(JSON));
    }

    private <T> T call(java.util.function.Supplier<T> request) {
        try {
            return request.get();
        } catch (RestClientResponseException e) {
            String message = agentError(e);
            switch (e.getStatusCode().value()) {
                case 400 -> throw new BadRequestException(message);
                case 409 -> throw new ConflictException(message);
                case 401 -> throw new ServiceUnavailableException("Counter agent rejected the backend's token (check VISION_AGENT_TOKEN / agent_token.txt)");
                default -> {
                    log.warn("Counter agent answered {}: {}", e.getStatusCode().value(), message);
                    throw new ServiceUnavailableException("Counter agent error: " + message);
                }
            }
        } catch (RestClientException e) {
            log.warn("Counter agent unreachable: {}", e.getMessage());
            throw new ServiceUnavailableException("Counter agent is not running on this computer (start it with: python vision-service/agent.py)");
        }
    }

    private String token() {
        if (configuredToken != null && !configuredToken.isBlank()) {
            return configuredToken;
        }
        try {
            return Files.readString(tokenFile).trim();
        } catch (IOException e) {
            throw new ServiceUnavailableException("Counter agent token not found; start the agent once (python vision-service/agent.py)");
        }
    }

    private static String agentError(RestClientResponseException e) {
        try {
            Object error = e.getResponseBodyAs(Map.class).get("error");
            return error != null ? error.toString() : e.getStatusText();
        } catch (RuntimeException ignored) {
            return e.getStatusText();
        }
    }
}
