package com.transport.backend;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AuthAndSecurityTest extends IntegrationTestBase {

    private Map<String, Object> registration(String email) {
        Map<String, Object> body = new HashMap<>();
        body.put("name", "New Passenger");
        body.put("email", email);
        body.put("password", "pass1234");
        return body;
    }

    @Test
    void registerCreatesUserWithHashedPasswordAndReturnsToken() throws Exception {
        String email = unique("new") + "@test.local";
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content(toJson(registration(email))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.token").isNotEmpty())
                .andExpect(jsonPath("$.role").value("USER"));
        String stored = userRepository.findByEmail(email).orElseThrow().getPassword();
        assertThat(stored).isNotEqualTo("pass1234").startsWith("$2");
    }

    @Test
    void duplicateEmailIsRejectedWith409() throws Exception {
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content(toJson(registration(USER_EMAIL.toUpperCase()))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("An account with this email already exists"));
    }

    @Test
    void invalidRegistrationReturnsFieldErrors() throws Exception {
        Map<String, Object> body = registration("not-an-email");
        body.put("password", "123");
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content(toJson(body)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.validationErrors.email").exists())
                .andExpect(jsonPath("$.validationErrors.password").exists());
    }

    @Test
    void adminRegistrationRequiresValidCode() throws Exception {
        Map<String, Object> body = registration(unique("adm") + "@test.local");
        body.put("role", "ADMIN");
        body.put("adminCode", "wrong");
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content(toJson(body)))
                .andExpect(status().isBadRequest());

        body.put("adminCode", "test-admin-code");
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content(toJson(body)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.role").value("ADMIN"));
    }

    @Test
    void wrongPasswordAndUnknownUserAreRejectedWith401() throws Exception {
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(Map.of("email", USER_EMAIL, "password", "wrong-pass"))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Invalid email or password"));
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(Map.of("email", "nobody@test.local", "password", "whatever"))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void protectedEndpointsRequireToken() throws Exception {
        mvc.perform(get("/api/buses")).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.status").value(401));
        mvc.perform(get("/api/auth/profile")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/buses").header("Authorization", "Bearer invalid.token.value")).andExpect(status().isUnauthorized());
        mvc.perform(auth(get("/api/auth/profile"), userToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(USER_EMAIL))
                .andExpect(jsonPath("$.password").doesNotExist());
    }

    @Test
    void passengersCannotUseAdminOperations() throws Exception {
        mvc.perform(auth(post("/api/buses"), userToken).content(toJson(Map.of("busNumber", "X-1", "capacity", 10))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403));
        mvc.perform(auth(delete("/api/routes/1"), userToken)).andExpect(status().isForbidden());
        mvc.perform(auth(get("/api/admin/users"), userToken)).andExpect(status().isForbidden());
        mvc.perform(auth(post("/api/location/update"), userToken)
                        .content(toJson(Map.of("busId", 1, "latitude", 1.0, "longitude", 1.0))))
                .andExpect(status().isForbidden());
    }

    @Test
    void adminCanListUsersButNotDeleteOrDemoteThemselves() throws Exception {
        mvc.perform(auth(get("/api/admin/users"), adminToken)).andExpect(status().isOk());
        long adminId = userRepository.findByEmail(ADMIN_EMAIL).orElseThrow().getId();
        mvc.perform(auth(delete("/api/admin/users/" + adminId), adminToken)).andExpect(status().isBadRequest());
        mvc.perform(auth(put("/api/admin/users/" + adminId + "/role"), adminToken).content(toJson(Map.of("role", "USER"))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void adminCanPromoteAndDeleteAnotherUser() throws Exception {
        String email = unique("promo") + "@test.local";
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content(toJson(registration(email))))
                .andExpect(status().isCreated());
        long id = userRepository.findByEmail(email).orElseThrow().getId();
        mvc.perform(auth(put("/api/admin/users/" + id + "/role"), adminToken).content(toJson(Map.of("role", "ADMIN"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.role").value("ADMIN"));
        mvc.perform(auth(delete("/api/admin/users/" + id), adminToken)).andExpect(status().isNoContent());
        assertThat(userRepository.findByEmail(email)).isEmpty();
    }
}
