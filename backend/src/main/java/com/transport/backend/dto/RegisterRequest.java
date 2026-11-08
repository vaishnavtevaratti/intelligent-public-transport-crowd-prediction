package com.transport.backend.dto;

import com.transport.backend.entity.Role;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class RegisterRequest {

    @NotBlank(message = "Name is required")
    @Size(min = 2, max = 80, message = "Name must be 2-80 characters")
    private String name;

    @NotBlank(message = "Email is required")
    @Email(message = "Email must be valid")
    @Size(max = 120, message = "Email is too long")
    private String email;

    @NotBlank(message = "Password is required")
    @Size(min = 6, max = 72, message = "Password must be 6-72 characters")
    private String password;

    @Pattern(regexp = "^$|^\\+?[0-9 ()-]{7,20}$", message = "Phone number is not valid")
    private String phone;

    /** USER (default) or ADMIN. ADMIN requires a valid adminCode. */
    private Role role = Role.USER;

    /** Administrator registration code configured on the server (app.auth.admin-registration-code). */
    private String adminCode;
}
