package com.cravero.cravbank.auth;

import com.cravero.cravbank.auth.dto.LoginRequest;
import com.cravero.cravbank.auth.dto.RefreshRequest;
import com.cravero.cravbank.auth.dto.RegisterRequest;
import com.cravero.cravbank.auth.dto.TokenResponse;
import com.cravero.cravbank.auth.dto.UserResponse;
import com.cravero.cravbank.security.AuthenticatedUser;
import com.cravero.cravbank.user.User;
import com.cravero.cravbank.user.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
@Tag(name = "Authentication")
public class AuthController {

    private final AuthService authService;
    private final UserService userService;

    public AuthController(AuthService authService, UserService userService) {
        this.authService = authService;
        this.userService = userService;
    }

    @Operation(summary = "Register a new user with an invitation code")
    @PostMapping("/register")
    public ResponseEntity<UserResponse> register(@Valid @RequestBody RegisterRequest request) {
        User user = authService.register(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(UserResponse.from(user));
    }

    @Operation(summary = "Log in and receive access + refresh tokens")
    @PostMapping("/login")
    public ResponseEntity<TokenResponse> login(@Valid @RequestBody LoginRequest request,
                                               HttpServletRequest servletRequest) {
        TokenResponse tokens = authService.login(request, servletRequest.getHeader("User-Agent"));
        return ResponseEntity.ok(tokens);
    }

    @Operation(summary = "Exchange a refresh token for a new token pair (rotation)")
    @PostMapping("/refresh")
    public ResponseEntity<TokenResponse> refresh(@Valid @RequestBody RefreshRequest request,
                                                 HttpServletRequest servletRequest) {
        TokenResponse tokens = authService.refresh(request.refreshToken(), servletRequest.getHeader("User-Agent"));
        return ResponseEntity.ok(tokens);
    }

    @Operation(summary = "Revoke the given refresh token", security = @SecurityRequirement(name = "bearerAuth"))
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@Valid @RequestBody RefreshRequest request) {
        authService.logout(request.refreshToken());
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Get the current authenticated user", security = @SecurityRequirement(name = "bearerAuth"))
    @GetMapping("/me")
    public ResponseEntity<UserResponse> me(@AuthenticationPrincipal AuthenticatedUser principal) {
        User user = userService.findById(principal.id());
        return ResponseEntity.ok(UserResponse.from(user));
    }
}
