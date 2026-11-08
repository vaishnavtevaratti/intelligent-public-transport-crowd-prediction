package com.transport.backend.controller;

import com.transport.backend.dto.AdminAnalyticsDto;
import com.transport.backend.dto.RoleUpdateRequest;
import com.transport.backend.dto.UserProfileDto;
import com.transport.backend.service.DashboardService;
import com.transport.backend.service.UserAdminService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** Administrator-only endpoints (ROLE_ADMIN is enforced for /api/admin/** in SecurityConfig). */
@RestController
@RequestMapping("/api/admin")
@Tag(name = "Administration", description = "User management and system analytics (ADMIN only)")
public class AdminController {

    private final UserAdminService userAdminService;
    private final DashboardService dashboardService;

    public AdminController(UserAdminService userAdminService, DashboardService dashboardService) {
        this.userAdminService = userAdminService;
        this.dashboardService = dashboardService;
    }

    @GetMapping("/analytics")
    @Operation(summary = "System analytics: users, buses by status, prediction statistics, ML model status")
    public ResponseEntity<AdminAnalyticsDto> analytics() {
        return ResponseEntity.ok(dashboardService.getAdminAnalytics());
    }

    @GetMapping("/users")
    @Operation(summary = "List all users")
    public ResponseEntity<List<UserProfileDto>> users() {
        return ResponseEntity.ok(userAdminService.listUsers());
    }

    @PutMapping("/users/{id}/role")
    @Operation(summary = "Change a user's role (USER / ADMIN)")
    public ResponseEntity<UserProfileDto> changeRole(@PathVariable Long id, @Valid @RequestBody RoleUpdateRequest request,
                                                     Authentication authentication) {
        return ResponseEntity.ok(userAdminService.changeRole(id, request.getRole(), authentication.getName()));
    }

    @DeleteMapping("/users/{id}")
    @Operation(summary = "Delete a user account")
    public ResponseEntity<Void> deleteUser(@PathVariable Long id, Authentication authentication) {
        userAdminService.deleteUser(id, authentication.getName());
        return ResponseEntity.noContent().build();
    }
}
