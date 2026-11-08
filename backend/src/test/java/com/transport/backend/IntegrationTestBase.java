package com.transport.backend;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.transport.backend.entity.Role;
import com.transport.backend.entity.User;
import com.transport.backend.repository.UserRepository;
import com.transport.backend.service.ml.CrowdModelClient;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Full Spring Boot context against an in-memory H2 database (see src/test/resources/application.properties).
 * The Python ML service is replaced by a Mockito mock so tests are deterministic.
 */
@SpringBootTest
@AutoConfigureMockMvc
public abstract class IntegrationTestBase {

    protected static final String ADMIN_EMAIL = "admin@test.local";
    protected static final String USER_EMAIL = "user@test.local";
    protected static final String PASSWORD = "secret123";
    private static final AtomicInteger COUNTER = new AtomicInteger();

    @Autowired
    protected MockMvc mvc;
    @Autowired
    protected ObjectMapper json;
    @Autowired
    protected UserRepository userRepository;
    @Autowired
    protected PasswordEncoder passwordEncoder;
    @MockBean
    protected CrowdModelClient modelClient;

    protected String adminToken;
    protected String userToken;

    @BeforeEach
    void createAccounts() throws Exception {
        ensureUser(ADMIN_EMAIL, Role.ADMIN);
        ensureUser(USER_EMAIL, Role.USER);
        adminToken = login(ADMIN_EMAIL, PASSWORD);
        userToken = login(USER_EMAIL, PASSWORD);
    }

    private void ensureUser(String email, Role role) {
        if (userRepository.findByEmail(email).isEmpty()) {
            userRepository.save(User.builder().name(role + " Tester").email(email)
                    .password(passwordEncoder.encode(PASSWORD)).role(role).build());
        }
    }

    protected String login(String email, String password) throws Exception {
        MvcResult result = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(Map.of("email", email, "password", password))))
                .andExpect(status().isOk()).andReturn();
        return read(result).get("token").asText();
    }

    protected MockHttpServletRequestBuilder auth(MockHttpServletRequestBuilder request, String token) {
        return request.header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON);
    }

    protected String toJson(Object value) throws Exception {
        return json.writeValueAsString(value);
    }

    protected JsonNode read(MvcResult result) throws Exception {
        return json.readTree(result.getResponse().getContentAsString());
    }

    protected static String unique(String prefix) {
        return prefix + COUNTER.incrementAndGet() + "-" + System.nanoTime() % 100000;
    }

    /** Creates a route with three stops through the admin API and returns its id. */
    protected long createRoute() throws Exception {
        Map<String, Object> body = Map.of(
                "source", unique("Src"), "destination", unique("Dst"), "description", "test",
                "stops", java.util.List.of(
                        Map.of("name", "Stop A", "latitude", 16.7000, "longitude", 74.2400),
                        Map.of("name", "Stop B", "latitude", 16.7100, "longitude", 74.2500),
                        Map.of("name", "Stop C", "latitude", 16.7200, "longitude", 74.2600)));
        MvcResult result = mvc.perform(auth(post("/api/routes"), adminToken).content(toJson(body)))
                .andExpect(status().isCreated()).andReturn();
        return read(result).get("id").asLong();
    }

    protected long createBus(Long routeId) throws Exception {
        java.util.HashMap<String, Object> body = new java.util.HashMap<>();
        String number = unique("TB-");
        body.put("busNumber", number.substring(0, Math.min(18, number.length())));
        body.put("capacity", 40);
        body.put("routeId", routeId);
        MvcResult result = mvc.perform(auth(post("/api/buses"), adminToken).content(toJson(body)))
                .andExpect(status().isCreated()).andReturn();
        return read(result).get("id").asLong();
    }
}
