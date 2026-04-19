-- Code d'invitation initial pour créer le premier utilisateur.
-- À consommer ou révoquer après usage.
INSERT INTO invitations (code, expires_at)
VALUES ('BOOTSTRAP-ADMIN', now() + interval '30 days');
