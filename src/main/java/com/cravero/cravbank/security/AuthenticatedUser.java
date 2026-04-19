package com.cravero.cravbank.security;

import com.cravero.cravbank.user.UserRole;

import java.util.UUID;

public record AuthenticatedUser(UUID id, String email, UserRole role) {
}
