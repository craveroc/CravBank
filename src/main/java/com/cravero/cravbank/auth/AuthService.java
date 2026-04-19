package com.cravero.cravbank.auth;

import com.cravero.cravbank.auth.dto.LoginRequest;
import com.cravero.cravbank.auth.dto.RegisterRequest;
import com.cravero.cravbank.auth.dto.TokenResponse;
import com.cravero.cravbank.common.RefreshTokenInvalidException;
import com.cravero.cravbank.invitation.Invitation;
import com.cravero.cravbank.invitation.InvitationService;
import com.cravero.cravbank.security.JwtService;
import com.cravero.cravbank.user.User;
import com.cravero.cravbank.user.UserService;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Service
public class AuthService {

    private final UserService userService;
    private final InvitationService invitationService;
    private final JwtService jwtService;
    private final RefreshTokenRepository refreshTokenRepository;
    private final AuthenticationManager authenticationManager;

    public AuthService(UserService userService,
                       InvitationService invitationService,
                       JwtService jwtService,
                       RefreshTokenRepository refreshTokenRepository,
                       AuthenticationManager authenticationManager) {
        this.userService = userService;
        this.invitationService = invitationService;
        this.jwtService = jwtService;
        this.refreshTokenRepository = refreshTokenRepository;
        this.authenticationManager = authenticationManager;
    }

    @Transactional
    public User register(RegisterRequest request) {
        Invitation invitation = invitationService.validate(request.invitationCode());
        User user = userService.create(request.email(), request.password(), invitation);
        invitationService.consume(invitation.getCode(), user.getId());
        return user;
    }

    @Transactional
    public TokenResponse login(LoginRequest request, String deviceInfo) {
        authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(request.email().trim().toLowerCase(), request.password()));
        User user = userService.findByEmail(request.email());
        return issueTokens(user, deviceInfo);
    }

    @Transactional
    public TokenResponse refresh(String refreshToken, String deviceInfo) {
        String hash = jwtService.hashRefreshToken(refreshToken);
        RefreshToken stored = refreshTokenRepository.findByTokenHash(hash)
                .orElseThrow(RefreshTokenInvalidException::new);
        if (!stored.isActive()) {
            throw new RefreshTokenInvalidException();
        }
        stored.revoke();
        refreshTokenRepository.save(stored);
        return issueTokens(stored.getUser(), deviceInfo);
    }

    @Transactional
    public void logout(String refreshToken) {
        if (refreshToken == null || refreshToken.isBlank()) {
            return;
        }
        String hash = jwtService.hashRefreshToken(refreshToken);
        refreshTokenRepository.findByTokenHash(hash).ifPresent(rt -> {
            rt.revoke();
            refreshTokenRepository.save(rt);
        });
    }

    private TokenResponse issueTokens(User user, String deviceInfo) {
        String accessToken = jwtService.generateAccessToken(user);
        String rawRefresh = jwtService.generateRefreshToken();
        String hash = jwtService.hashRefreshToken(rawRefresh);
        Instant expiresAt = Instant.now().plus(jwtService.getRefreshTokenTtl());
        refreshTokenRepository.save(new RefreshToken(user, hash, expiresAt, deviceInfo));
        return new TokenResponse(accessToken, rawRefresh, jwtService.getAccessTokenTtl().getSeconds(), "Bearer");
    }
}
