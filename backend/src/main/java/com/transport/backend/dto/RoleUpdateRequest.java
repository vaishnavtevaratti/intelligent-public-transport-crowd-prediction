package com.transport.backend.dto;

import com.transport.backend.entity.Role;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class RoleUpdateRequest {

    @NotNull(message = "Role is required")
    private Role role;
}
