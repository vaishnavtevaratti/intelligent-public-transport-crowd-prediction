package com.transport.backend.service;

import com.transport.backend.dto.AuthResponse;
import com.transport.backend.dto.LoginRequest;
import com.transport.backend.dto.RegisterRequest;
import com.transport.backend.dto.UserProfileDto;
import com.transport.backend.entity.Role;
import com.transport.backend.entity.User;
import com.transport.backend.exception.BadRequestException;
import com.transport.backend.exception.ConflictException;
import com.transport.backend.exception.ResourceNotFoundException;
import com.transport.backend.repository.UserRepository;
import com.transport.backend.security.JwtTokenProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuthenticationManager authenticationManager;
    private final JwtTokenProvider tokenProvider;
    private final String adminRegistrationCode;

    @Autowired
    public AuthService(UserRepository userRepository,
                       PasswordEncoder passwordEncoder,
                       AuthenticationManager authenticationManager,
                       JwtTokenProvider tokenProvider,
                       @Value("${app.auth.admin-registration-code:}") String adminRegistrationCode) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.authenticationManager = authenticationManager;
        this.tokenProvider = tokenProvider;
        this.adminRegistrationCode = adminRegistrationCode;
    }

    /**
     * Registers a passenger (USER). Registering as ADMIN is only possible with the
     * administrator registration code configured on the server, so nobody can grant
     * themselves admin rights from the public sign-up form.
     */
    @Transactional
    public AuthResponse registerUser(RegisterRequest registerRequest) {
        String email = registerRequest.getEmail().trim().toLowerCase();
        if (userRepository.existsByEmailIgnoreCase(email)) {
            throw new ConflictException("An account with this email already exists");
        }

        Role role = registerRequest.getRole() != null ? registerRequest.getRole() : Role.USER;
        if (role == Role.ADMIN && !isValidAdminCode(registerRequest.getAdminCode())) {
            throw new BadRequestException(adminRegistrationCode.isBlank()
                    ? "Administrator self-registration is disabled. Ask an existing administrator to grant you admin access."
                    : "Invalid administrator registration code");
        }

        User user = User.builder()
                .name(registerRequest.getName().trim())
                .email(email)
                .password(passwordEncoder.encode(registerRequest.getPassword()))
                .phone(registerRequest.getPhone() == null || registerRequest.getPhone().isBlank() ? null : registerRequest.getPhone().trim())
                .role(role)
                .build();

        User savedUser = userRepository.save(user);
        log.info("Registered new {} account id={}", savedUser.getRole(), savedUser.getId());
        return toResponse(savedUser, tokenProvider.generateTokenFromEmail(savedUser.getEmail()));
    }

    public AuthResponse loginUser(LoginRequest loginRequest) {
        String email = loginRequest.getEmail().trim().toLowerCase();
        User user = userRepository.findByEmailIgnoreCase(email).orElse(null);
        // Authenticate with the stored email so logins are case-insensitive.
        Authentication authentication = authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(user != null ? user.getEmail() : email, loginRequest.getPassword()));
        String token = tokenProvider.generateToken(authentication);
        return toResponse(user, token);
    }

    @Transactional(readOnly = true)
    public UserProfileDto getUserProfile(String email) {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new ResourceNotFoundException("User", "email", email));
        return toProfile(user);
    }

    public static UserProfileDto toProfile(User user) {
        return UserProfileDto.builder()
                .id(user.getId())
                .name(user.getName())
                .email(user.getEmail())
                .phone(user.getPhone())
                .role(user.getRole())
                .createdAt(user.getCreatedAt())
                .build();
    }

    private boolean isValidAdminCode(String code) {
        if (adminRegistrationCode == null || adminRegistrationCode.isBlank() || code == null) {
            return false;
        }
        return MessageDigest.isEqual(adminRegistrationCode.getBytes(StandardCharsets.UTF_8), code.getBytes(StandardCharsets.UTF_8));
    }

    private AuthResponse toResponse(User user, String token) {
        return AuthResponse.builder()
                .token(token)
                .tokenType("Bearer")
                .id(user.getId())
                .name(user.getName())
                .email(user.getEmail())
                .role(user.getRole())
                .build();
    }
}
