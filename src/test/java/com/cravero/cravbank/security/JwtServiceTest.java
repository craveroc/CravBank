package com.cravero.cravbank.security;

import com.cravero.cravbank.user.User;
import com.cravero.cravbank.user.UserRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtServiceTest {

    private JwtService jwtService;

    @BeforeEach
    void setUp() {
        JwtProperties props = new JwtProperties();
        props.setSecret("a-very-long-test-secret-that-is-at-least-32-bytes!!");
        props.setIssuer("cravbank-test");
        props.setAccessTokenTtl(Duration.ofMinutes(15));
        props.setRefreshTokenTtl(Duration.ofDays(30));
        jwtService = new JwtService(props);
    }

    @Test
    void generatesAndValidatesAccessToken() {
        User user = testUser();
        String token = jwtService.generateAccessToken(user);

        Optional<AuthenticatedUser> result = jwtService.validateAccessToken(token);

        assertThat(result).isPresent();
        assertThat(result.get().id()).isEqualTo(user.getId());
        assertThat(result.get().email()).isEqualTo(user.getEmail());
        assertThat(result.get().role()).isEqualTo(UserRole.USER);
    }

    @Test
    void rejectsTamperedToken() {
        User user = testUser();
        String token = jwtService.generateAccessToken(user);
        String tampered = token.substring(0, token.length() - 2) + "xx";

        assertThat(jwtService.validateAccessToken(tampered)).isEmpty();
    }

    @Test
    void rejectsExpiredToken() {
        JwtProperties props = new JwtProperties();
        props.setSecret("a-very-long-test-secret-that-is-at-least-32-bytes!!");
        props.setAccessTokenTtl(Duration.ofSeconds(-1));
        JwtService expiredService = new JwtService(props);

        String token = expiredService.generateAccessToken(testUser());

        assertThat(expiredService.validateAccessToken(token)).isEmpty();
    }

    @Test
    void hashingIsDeterministic() {
        String raw = jwtService.generateRefreshToken();
        assertThat(jwtService.hashRefreshToken(raw)).isEqualTo(jwtService.hashRefreshToken(raw));
    }

    @Test
    void rejectsShortSecret() {
        JwtProperties props = new JwtProperties();
        props.setSecret("too-short");
        assertThatThrownBy(() -> new JwtService(props)).isInstanceOf(IllegalStateException.class);
    }

    private User testUser() {
        User user = new User("alice@example.com", "hash", UserRole.USER, null);
        setField(user, "id", UUID.randomUUID());
        setField(user, "createdAt", Instant.now());
        setField(user, "updatedAt", Instant.now());
        return user;
    }

    private static void setField(Object target, String name, Object value) {
        try {
            Field field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            field.set(target, value);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
