package com.transport.backend.service;

import com.transport.backend.dto.UserProfileDto;
import com.transport.backend.entity.Role;
import com.transport.backend.entity.User;
import com.transport.backend.exception.BadRequestException;
import com.transport.backend.exception.ResourceNotFoundException;
import com.transport.backend.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** Administrator user management: list users, change roles, delete accounts. */
@Service
public class UserAdminService {

    private static final Logger log = LoggerFactory.getLogger(UserAdminService.class);

    private final UserRepository userRepository;

    public UserAdminService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Transactional(readOnly = true)
    public List<UserProfileDto> listUsers() {
        return userRepository.findAll(Sort.by("id")).stream().map(AuthService::toProfile).toList();
    }

    @Transactional
    public UserProfileDto changeRole(Long userId, Role role, String actingAdminEmail) {
        User user = find(userId);
        if (user.getEmail().equalsIgnoreCase(actingAdminEmail) && role != Role.ADMIN) {
            throw new BadRequestException("You cannot remove your own administrator role");
        }
        if (user.getRole() == Role.ADMIN && role != Role.ADMIN && userRepository.countByRole(Role.ADMIN) <= 1) {
            throw new BadRequestException("At least one administrator must remain");
        }
        user.setRole(role);
        log.info("Admin {} changed role of user {} to {}", actingAdminEmail, userId, role);
        return AuthService.toProfile(userRepository.save(user));
    }

    @Transactional
    public void deleteUser(Long userId, String actingAdminEmail) {
        User user = find(userId);
        if (user.getEmail().equalsIgnoreCase(actingAdminEmail)) {
            throw new BadRequestException("You cannot delete your own account");
        }
        if (user.getRole() == Role.ADMIN && userRepository.countByRole(Role.ADMIN) <= 1) {
            throw new BadRequestException("At least one administrator must remain");
        }
        userRepository.delete(user);
        log.info("Admin {} deleted user {}", actingAdminEmail, userId);
    }

    private User find(Long id) {
        return userRepository.findById(id).orElseThrow(() -> new ResourceNotFoundException("User", "id", id));
    }
}
