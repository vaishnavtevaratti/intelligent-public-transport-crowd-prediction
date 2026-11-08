package com.transport.backend;

import com.sun.net.httpserver.HttpServer;
import com.transport.backend.service.ml.CrowdModelClient;
import com.transport.backend.service.ml.CrowdModelClient.ModelInput;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/** predictCached (read-only views) sends only unseen inputs to the ML service; results keep their order. */
class ModelClientCacheTest {

    private final List<String> bodies = new CopyOnWriteArrayList<>();
    private HttpServer server;

    private CrowdModelClient client() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/predict", ex -> {
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            bodies.add(body);
            int n = body.split("\"hour\"").length - 1;   // one prediction per instance, level from the hour
            StringBuilder out = new StringBuilder("{\"model_version\":\"fake-1\",\"predictions\":[");
            for (int i = 0; i < n; i++) {
                out.append(i > 0 ? "," : "").append("{\"crowd_level\":\"LOW\",\"confidence\":0.5,\"probabilities\":{\"LOW\":0.5},\"out_of_training_range\":[]}");
            }
            byte[] bytes = out.append("]}").toString().getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, bytes.length);
            ex.getResponseBody().write(bytes);
            ex.close();
        });
        server.start();
        return new CrowdModelClient("http://127.0.0.1:" + server.getAddress().getPort(), 3000);
    }

    @AfterEach
    void stop() {
        if (server != null) server.stop(0);
    }

    @Test
    void identicalInputsAreServedFromCacheAndOnlyNewOnesAreSent() throws Exception {
        CrowdModelClient client = client();
        ModelInput a = new ModelInput(8, 0, 5.6, 4, 50);
        ModelInput b = new ModelInput(9, 0, 5.6, 4, 50);
        ModelInput c = new ModelInput(10, 0, 5.6, 4, 50);

        assertThat(client.predictCached(List.of(a, b))).hasSize(2);
        assertThat(client.predictCached(List.of(b, a))).hasSize(2);          // both cached: no call
        assertThat(bodies).hasSize(1);
        assertThat(client.predictCached(List.of(a, c, b))).hasSize(3);       // only c is sent
        assertThat(bodies).hasSize(2);
        assertThat(bodies.get(1)).contains("\"hour\":10").doesNotContain("\"hour\":8");

        client.predict(List.of(a));                                         // stored predictions always call the model
        assertThat(bodies).hasSize(3);
    }
}
