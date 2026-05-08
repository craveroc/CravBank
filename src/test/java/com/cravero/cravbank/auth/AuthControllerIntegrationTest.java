package com.cravero.cravbank.auth;

import com.cravero.cravbank.AbstractIntegrationTest;
import com.cravero.cravbank.auth.dto.LoginRequest;
import com.cravero.cravbank.auth.dto.RefreshRequest;
import com.cravero.cravbank.auth.dto.RegisterRequest;
import com.cravero.cravbank.auth.dto.TokenResponse;
import com.cravero.cravbank.invitation.Invitation;
import com.cravero.cravbank.invitation.InvitationRepository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AuthControllerIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private InvitationRepository invitationRepository;

    @Autowired
    private RefreshTokenRepository refreshTokenRepository;

    @Autowired
    private com.cravero.cravbank.user.UserRepository userRepository;

    @BeforeEach
    void cleanup() {
        refreshTokenRepository.deleteAll();
        userRepository.deleteAll();
        invitationRepository.deleteAll();
    }

    private Invitation createInvitation() {
        return createInvitation(Instant.now().plusSeconds(3600));
    }

    private Invitation createInvitation(Instant expiresAt) {
        Invitation inv = new Invitation("INV-" + UUID.randomUUID().toString().substring(0, 8), expiresAt, null);
        return invitationRepository.save(inv);
    }

    private Invitation createUsedInvitation() {
        Invitation inv = createInvitation();
        inv.markUsed(null);
        return invitationRepository.save(inv);
    }

    private String json(Object body) throws Exception {
        return objectMapper.writeValueAsString(body);
    }

    private MvcResult register(String email, String password, String code) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new RegisterRequest(email, password, code))))
                .andReturn();
    }

    private TokenResponse loginOk(String email, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new LoginRequest(email, password))))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readValue(result.getResponse().getContentAsString(), TokenResponse.class);
    }

    @Test
    void register_withValidInvitation_returns201() throws Exception {
        Invitation inv = createInvitation();
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new RegisterRequest("alice@example.com", "password123", inv.getCode()))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.email").value("alice@example.com"))
                .andExpect(jsonPath("$.role").value("USER"));
    }

    @Test
    void register_withInvalidCode_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new RegisterRequest("bob@example.com", "password123", "UNKNOWN-CODE"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVITATION_INVALID"));
    }

    @Test
    void register_withExpiredInvitation_returns400() throws Exception {
        Invitation inv = createInvitation(Instant.now().minusSeconds(10));
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new RegisterRequest("carol@example.com", "password123", inv.getCode()))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVITATION_INVALID"));
    }

    @Test
    void register_withUsedInvitation_returns400() throws Exception {
        Invitation inv = createUsedInvitation();
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new RegisterRequest("dave@example.com", "password123", inv.getCode()))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVITATION_INVALID"));
    }

    @Test
    void register_duplicateEmail_returns409() throws Exception {
        Invitation inv1 = createInvitation();
        Invitation inv2 = createInvitation();
        register("eve@example.com", "password123", inv1.getCode());

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new RegisterRequest("eve@example.com", "password123", inv2.getCode()))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));
    }

    @Test
    void register_weakPassword_returns400() throws Exception {
        Invitation inv = createInvitation();
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new RegisterRequest("frank@example.com", "short", inv.getCode()))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void login_validCredentials_returnsTokens() throws Exception {
        Invitation inv = createInvitation();
        register("gina@example.com", "password123", inv.getCode());

        TokenResponse tokens = loginOk("gina@example.com", "password123");
        assertThat(tokens.accessToken()).isNotBlank();
        assertThat(tokens.refreshToken()).isNotBlank();
        assertThat(tokens.tokenType()).isEqualTo("Bearer");
        assertThat(tokens.expiresIn()).isPositive();
    }

    @Test
    void login_wrongPassword_returns401() throws Exception {
        Invitation inv = createInvitation();
        register("hank@example.com", "password123", inv.getCode());

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new LoginRequest("hank@example.com", "wrong-password"))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void login_unknownEmail_returns401() throws Exception {
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new LoginRequest("ghost@example.com", "password123"))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void refresh_validToken_rotatesAndReturnsNewTokens() throws Exception {
        Invitation inv = createInvitation();
        register("ivy@example.com", "password123", inv.getCode());
        TokenResponse initial = loginOk("ivy@example.com", "password123");

        MvcResult result = mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new RefreshRequest(initial.refreshToken()))))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(body.get("refreshToken").asText()).isNotEqualTo(initial.refreshToken());
        assertThat(body.get("accessToken").asText()).isNotBlank();

        mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new RefreshRequest(initial.refreshToken()))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void refresh_unknownToken_returns401() throws Exception {
        mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new RefreshRequest("totally-bogus-refresh-token"))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("REFRESH_TOKEN_INVALID"));
    }

    @Test
    void logout_validToken_revokesIt() throws Exception {
        Invitation inv = createInvitation();
        register("jake@example.com", "password123", inv.getCode());
        TokenResponse tokens = loginOk("jake@example.com", "password123");

        mockMvc.perform(post("/api/v1/auth/logout")
                        .header("Authorization", "Bearer " + tokens.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new RefreshRequest(tokens.refreshToken()))))
                .andExpect(status().isNoContent());

        mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new RefreshRequest(tokens.refreshToken()))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void me_withoutToken_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/auth/me"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void me_withValidToken_returnsCurrentUser() throws Exception {
        Invitation inv = createInvitation();
        register("kate@example.com", "password123", inv.getCode());
        TokenResponse tokens = loginOk("kate@example.com", "password123");

        mockMvc.perform(get("/api/v1/auth/me")
                        .header("Authorization", "Bearer " + tokens.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("kate@example.com"));
    }

    @Test
    void me_withInvalidToken_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/auth/me")
                        .header("Authorization", "Bearer invalid.jwt.token"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void register_missingFields_returns400() throws Exception {
        Invitation inv = createInvitation();
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new RegisterRequest("", "password123", inv.getCode()))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }
}
