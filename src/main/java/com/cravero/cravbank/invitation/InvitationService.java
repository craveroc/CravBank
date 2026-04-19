package com.cravero.cravbank.invitation;

import com.cravero.cravbank.common.InvitationInvalidException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

@Service
public class InvitationService {

    private static final char[] CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789".toCharArray();
    private static final SecureRandom RANDOM = new SecureRandom();

    private final InvitationRepository invitationRepository;

    public InvitationService(InvitationRepository invitationRepository) {
        this.invitationRepository = invitationRepository;
    }

    @Transactional(readOnly = true)
    public Invitation validate(String code) {
        Invitation invitation = invitationRepository.findByCode(code)
                .orElseThrow(() -> new InvitationInvalidException("Unknown invitation code"));
        if (invitation.getUsedAt() != null) {
            throw new InvitationInvalidException("Invitation already used");
        }
        if (invitation.getExpiresAt().isBefore(Instant.now())) {
            throw new InvitationInvalidException("Invitation expired");
        }
        return invitation;
    }

    @Transactional
    public Invitation consume(String code, UUID userId) {
        Invitation invitation = validate(code);
        invitation.markUsed(userId);
        return invitationRepository.save(invitation);
    }

    @Transactional
    public Invitation create(UUID createdBy, Duration ttl) {
        String code = generateCode();
        Invitation invitation = new Invitation(code, Instant.now().plus(ttl), createdBy);
        return invitationRepository.save(invitation);
    }

    private String generateCode() {
        StringBuilder sb = new StringBuilder(9);
        for (int i = 0; i < 4; i++) {
            sb.append(CHARS[RANDOM.nextInt(CHARS.length)]);
        }
        sb.append('-');
        for (int i = 0; i < 4; i++) {
            sb.append(CHARS[RANDOM.nextInt(CHARS.length)]);
        }
        return sb.toString();
    }
}
