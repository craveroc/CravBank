# Phase 1 — Fondations & Authentification

Objectif : disposer d'un serveur Spring Boot exécutable en local (profil `dev`) et déployable (profil `prod`), avec base de données PostgreSQL, migrations Flyway, et un système d'authentification JWT complet basé sur des invitations.

**Critère de sortie de phase** : un utilisateur peut s'inscrire avec un code d'invitation, se connecter, rafraîchir son token, se déconnecter, et appeler `GET /auth/me` avec son access token. Les tests d'intégration couvrent tous ces flux.

---

## 1. Dépendances Gradle

Modifier `build.gradle.kts` pour ajouter :

```kotlin
dependencies {
    // Déjà présent
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:3.0.2")

    // À ajouter
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-actuator")

    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")

    runtimeOnly("org.postgresql:postgresql")

    implementation("io.jsonwebtoken:jjwt-api:0.12.6")
    runtimeOnly("io.jsonwebtoken:jjwt-impl:0.12.6")
    runtimeOnly("io.jsonwebtoken:jjwt-jackson:0.12.6")

    // Tests
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.springframework.security:spring-security-test")
    testImplementation("org.testcontainers:postgresql:1.20.3")
    testImplementation("org.testcontainers:junit-jupiter:1.20.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
```

Vérifier que la version de Spring Boot 4.0.5 est compatible avec les versions de Testcontainers et JJWT ci-dessus. Ajuster si nécessaire.

---

## 2. Configuration applicative

### Fichiers à créer

```
src/main/resources/
├── application.yml          (commun — remplace application.properties)
├── application-dev.yml      (profil dev — local + ngrok)
└── application-prod.yml     (profil prod — Oracle VPS)
```

### `application.yml` (commun)

```yaml
spring:
  application:
    name: cravbank
  jpa:
    hibernate:
      ddl-auto: validate
    properties:
      hibernate:
        jdbc:
          time_zone: UTC
  flyway:
    enabled: true
    locations: classpath:db/migration

server:
  port: 8080
  error:
    include-message: always
    include-binding-errors: always

cravbank:
  jwt:
    issuer: cravbank
    access-token-ttl: PT15M
    refresh-token-ttl: P30D
  security:
    invitation:
      default-ttl: P7D

springdoc:
  swagger-ui:
    path: /swagger-ui.html
  api-docs:
    path: /v3/api-docs
```

### `application-dev.yml`

```yaml
spring:
  config:
    activate:
      on-profile: dev
  datasource:
    url: jdbc:postgresql://localhost:5432/cravbank
    username: cravbank
    password: ${DB_PASSWORD:cravbank}
  jpa:
    show-sql: true

logging:
  level:
    com.cravero.cravbank: DEBUG
    org.springframework.security: DEBUG

cravbank:
  jwt:
    secret: ${JWT_SECRET:dev-secret-change-me-at-least-32-bytes-long-!!}
  cors:
    allowed-origins: "*"
```

### `application-prod.yml`

```yaml
spring:
  config:
    activate:
      on-profile: prod
  datasource:
    url: ${DB_URL}
    username: ${DB_USER}
    password: ${DB_PASSWORD}

cravbank:
  jwt:
    secret: ${JWT_SECRET}
  cors:
    allowed-origins: ${CORS_ALLOWED_ORIGINS}
```

### Fichier `.env.example` (à versionner)

```
DB_URL=jdbc:postgresql://localhost:5432/cravbank
DB_USER=cravbank
DB_PASSWORD=cravbank
JWT_SECRET=<32+ bytes random base64>
```

Supprimer `application.properties` existant une fois `application.yml` en place.

---

## 3. Structure des packages à créer

```
com.cravero.cravbank
├── CravbankApplication.java                 (déjà là)
├── config/
│   ├── SecurityConfig.java
│   ├── OpenApiConfig.java
│   └── CorsConfig.java
├── security/
│   ├── JwtService.java                      (génération + validation des JWT)
│   ├── JwtAuthenticationFilter.java         (filter Spring Security)
│   ├── CustomUserDetailsService.java
│   └── AuthenticatedUser.java               (record: userId, email, roles)
├── user/
│   ├── User.java                            (entité JPA)
│   ├── UserRepository.java
│   ├── UserRole.java                        (enum USER, ADMIN)
│   └── UserService.java
├── auth/
│   ├── AuthController.java                  (/auth/*)
│   ├── AuthService.java
│   ├── RefreshToken.java                    (entité JPA)
│   ├── RefreshTokenRepository.java
│   └── dto/
│       ├── RegisterRequest.java
│       ├── LoginRequest.java
│       ├── RefreshRequest.java
│       ├── TokenResponse.java
│       └── UserResponse.java
├── invitation/
│   ├── Invitation.java
│   ├── InvitationRepository.java
│   └── InvitationService.java
└── common/
    ├── ApiError.java
    ├── GlobalExceptionHandler.java
    └── ErrorCode.java                       (enum)
```

---

## 4. Migrations Flyway

Créer `src/main/resources/db/migration/V1__init_auth.sql` :

```sql
CREATE EXTENSION IF NOT EXISTS "pgcrypto";

CREATE TABLE users (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    email VARCHAR(255) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    role VARCHAR(32) NOT NULL DEFAULT 'USER',
    active BOOLEAN NOT NULL DEFAULT true,
    invitation_id UUID,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_users_email ON users(lower(email));

CREATE TABLE invitations (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    code VARCHAR(32) NOT NULL UNIQUE,
    created_by UUID REFERENCES users(id) ON DELETE SET NULL,
    used_by UUID REFERENCES users(id) ON DELETE SET NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    used_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_invitations_code ON invitations(code);

ALTER TABLE users
    ADD CONSTRAINT fk_users_invitation
    FOREIGN KEY (invitation_id) REFERENCES invitations(id) ON DELETE SET NULL;

CREATE TABLE refresh_tokens (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    token_hash VARCHAR(255) NOT NULL UNIQUE,
    device_info VARCHAR(255),
    expires_at TIMESTAMPTZ NOT NULL,
    revoked_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_refresh_tokens_user ON refresh_tokens(user_id);
CREATE INDEX idx_refresh_tokens_hash ON refresh_tokens(token_hash);
```

Créer `V2__seed_admin_invitation.sql` (bootstrap pour créer le premier utilisateur admin) :

```sql
-- Code d'invitation initial utilisable par le premier utilisateur.
-- À SUPPRIMER ou marquer used dès qu'un admin existe.
INSERT INTO invitations (code, expires_at)
VALUES ('BOOTSTRAP-ADMIN', now() + interval '30 days');
```

**Note** : prévoir en phase ultérieure un mécanisme de promotion du premier utilisateur en `ADMIN` via SQL manuel ou config.

---

## 5. Entités JPA

### `User.java`

Mapping direct de la table `users`. Points clés :
- `@Id UUID id` — généré côté DB (`gen_random_uuid()`).
- `@Column(unique = true) String email` — normalisé en lowercase avant persistence.
- `String passwordHash`
- `@Enumerated(EnumType.STRING) UserRole role`
- `boolean active`
- `@ManyToOne Invitation invitation` (nullable)
- `Instant createdAt`, `Instant updatedAt` — via `@PrePersist` / `@PreUpdate`.
- Implémente `UserDetails` OU on passe par un wrapper `CustomUserDetailsService` → **préférer le wrapper** pour garder l'entité propre.

### `Invitation.java`

- `UUID id`, `String code` (unique), `User createdBy`, `User usedBy` (nullable), `Instant expiresAt`, `Instant usedAt`, `Instant createdAt`.
- Méthode `boolean isUsable()` → `usedAt == null && expiresAt.isAfter(now)`.

### `RefreshToken.java`

- `UUID id`, `User user`, `String tokenHash` (SHA-256 du token envoyé au client), `String deviceInfo`, `Instant expiresAt`, `Instant revokedAt`.
- Méthode `boolean isActive()` → `revokedAt == null && expiresAt.isAfter(now)`.
- **Rationale** : on stocke un hash plutôt que le token brut pour qu'une fuite de la DB ne permette pas de réutiliser des refresh tokens existants.

---

## 6. Services

### `JwtService`

Responsabilités :
- `String generateAccessToken(User user)` — claims : `sub=userId`, `email`, `role`, `iss=cravbank`, `iat`, `exp`.
- `String generateRefreshToken()` — UUID opaque renvoyé au client, hashé en DB.
- `Optional<Claims> validateAccessToken(String token)` — signature + expiration.
- `String hashRefreshToken(String raw)` — SHA-256 hex/base64.

Utilise `io.jsonwebtoken.Jwts` 0.12.x, clé HS256 dérivée de `cravbank.jwt.secret` (Base64 ou bytes bruts ≥ 32).

### `AuthService`

Méthodes :
- `UserResponse register(RegisterRequest)` — vérifie l'invitation, crée l'utilisateur, marque l'invitation `used`.
- `TokenResponse login(LoginRequest)` — authentifie (email + password), génère access + refresh, persiste hash du refresh.
- `TokenResponse refresh(String refreshToken)` — valide en DB, révoque l'ancien, émet un nouveau couple (rotation).
- `void logout(String refreshToken)` — marque `revoked_at`.

### `InvitationService`

- `Invitation consume(String code)` — atomique (verrou pessimiste ou check `used_at IS NULL`), lance `InvitationInvalidException` si invalide/expiré.
- `Invitation create(User createdBy, Duration ttl)` — génération code aléatoire 8 chars alphanumériques.

### `UserService`

- `User create(String email, String rawPassword, Invitation invitation)` — BCrypt, email normalisé.
- `User findByEmail(String email)`.

### `CustomUserDetailsService`

Implémente `UserDetailsService` pour Spring Security, retourne un `UserDetails` basé sur `User`.

---

## 7. Sécurité

### `SecurityConfig`

- Stateless (`SessionCreationPolicy.STATELESS`).
- CSRF désactivé.
- `JwtAuthenticationFilter` placé avant `UsernamePasswordAuthenticationFilter`.
- Routes publiques :
  - `POST /api/v1/auth/register`
  - `POST /api/v1/auth/login`
  - `POST /api/v1/auth/refresh`
  - `GET /actuator/health`
  - `GET /v3/api-docs/**`, `/swagger-ui/**`, `/swagger-ui.html`
- Tout le reste → authentifié.
- Bean `PasswordEncoder` → `BCryptPasswordEncoder(12)`.
- Bean `AuthenticationManager` exposé pour `AuthService`.

### `JwtAuthenticationFilter`

- Extrait le header `Authorization: Bearer ...`.
- Valide via `JwtService.validateAccessToken`.
- Si valide → construit un `UsernamePasswordAuthenticationToken` avec `AuthenticatedUser` en principal et le stocke dans le `SecurityContext`.
- Pas de levée d'exception si absence de header (le next filter refusera l'accès si la route est protégée).

### `CorsConfig`

- Allowed origins depuis `cravbank.cors.allowed-origins`.
- Methods : `GET, POST, PATCH, PUT, DELETE, OPTIONS`.
- Allowed headers : `Authorization, Content-Type`.

---

## 8. Controllers (endpoints)

`AuthController` sous `@RequestMapping("/api/v1/auth")` :

| Méthode | Path | Auth | Body | Réponse |
|---|---|---|---|---|
| POST | `/register` | public | `RegisterRequest` | `201 UserResponse` |
| POST | `/login` | public | `LoginRequest` | `200 TokenResponse` |
| POST | `/refresh` | public | `RefreshRequest` | `200 TokenResponse` |
| POST | `/logout` | auth | `RefreshRequest` | `204` |
| GET | `/me` | auth | – | `200 UserResponse` |

DTOs annotés `@Valid` avec `jakarta.validation` (`@Email`, `@NotBlank`, `@Size(min = 8)`).

---

## 9. Gestion des erreurs

### `ApiError`

```java
record ApiError(
    Instant timestamp,
    int status,
    String code,
    String message,
    String path,
    List<FieldError> details
) {}
```

### `GlobalExceptionHandler` (`@RestControllerAdvice`)

Mapping à implémenter :

| Exception | HTTP | Code |
|---|---|---|
| `MethodArgumentNotValidException` | 400 | `VALIDATION_ERROR` |
| `HttpMessageNotReadableException` | 400 | `VALIDATION_ERROR` |
| `BadCredentialsException` | 401 | `UNAUTHORIZED` |
| `AuthenticationException` (autre) | 401 | `UNAUTHORIZED` |
| `AccessDeniedException` | 403 | `FORBIDDEN` |
| `EmailAlreadyInUseException` | 409 | `CONFLICT` |
| `InvitationInvalidException` | 400 | `INVITATION_INVALID` |
| `RefreshTokenInvalidException` | 401 | `REFRESH_TOKEN_INVALID` |
| `Exception` (fallback) | 500 | `INTERNAL_ERROR` |

Toujours remplir `path` à partir de `HttpServletRequest`.

---

## 10. Tests

### Configuration

- `src/test/resources/application-test.yml` → active PostgreSQL via Testcontainers.
- Classe de base `AbstractIntegrationTest` :
  - `@SpringBootTest(webEnvironment = RANDOM_PORT)`
  - `@Testcontainers` + conteneur `PostgreSQLContainer("postgres:16-alpine")` statique
  - `@DynamicPropertySource` pour injecter l'URL JDBC
  - `@Autowired TestRestTemplate` ou `MockMvc`

### Tests d'intégration à écrire

`AuthControllerIntegrationTest` :
- `register_withValidInvitation_returns201`
- `register_withInvalidCode_returns400`
- `register_withExpiredInvitation_returns400`
- `register_withUsedInvitation_returns400`
- `register_duplicateEmail_returns409`
- `register_weakPassword_returns400`
- `login_validCredentials_returnsTokens`
- `login_wrongPassword_returns401`
- `login_unknownEmail_returns401`
- `refresh_validToken_rotatesAndReturnsNewTokens`
- `refresh_revokedToken_returns401`
- `refresh_expiredToken_returns401`
- `logout_validToken_revokesIt`
- `me_withoutToken_returns401`
- `me_withValidToken_returnsCurrentUser`
- `me_withExpiredToken_returns401`

### Tests unitaires

- `JwtServiceTest` : génération, validation, tampering détecté, expiration.
- `InvitationServiceTest` : consommation atomique, expiration, double usage refusé.

---

## 11. Documentation Swagger

- `OpenApiConfig` : définir un `@Bean OpenAPI` avec `SecurityScheme` Bearer JWT.
- Annoter `AuthController` avec `@Tag("Authentication")`.
- Annoter les endpoints avec `@Operation` et `@ApiResponse`.
- Vérifier que Swagger UI est accessible sans auth sur `/swagger-ui.html`.

---

## 12. Environnement local

### docker-compose.yml (à la racine)

```yaml
services:
  postgres:
    image: postgres:16-alpine
    environment:
      POSTGRES_DB: cravbank
      POSTGRES_USER: cravbank
      POSTGRES_PASSWORD: cravbank
    ports:
      - "5432:5432"
    volumes:
      - cravbank_pgdata:/var/lib/postgresql/data

volumes:
  cravbank_pgdata:
```

### Commandes

```bash
# Démarrer Postgres
docker compose up -d

# Lancer l'app en dev
./gradlew bootRun --args='--spring.profiles.active=dev'

# Tests
./gradlew test

# Arrêter Postgres
docker compose down
```

---

## 13. Checklist d'exécution

Ordre recommandé (chaque étape buildable et testable individuellement) :

- [ ] 1. Ajouter les dépendances Gradle, `./gradlew build` passe.
- [ ] 2. Créer `docker-compose.yml` + `.env.example`.
- [ ] 3. Créer `application.yml`, `application-dev.yml`, `application-prod.yml` ; supprimer `application.properties`.
- [ ] 4. Créer les migrations Flyway V1 et V2 ; `./gradlew bootRun` démarre sans erreur et les tables apparaissent.
- [ ] 5. Créer les entités JPA (`User`, `Invitation`, `RefreshToken`) + repositories + enum `UserRole`.
- [ ] 6. Créer `JwtService` + tests unitaires.
- [ ] 7. Créer `CustomUserDetailsService`, `PasswordEncoder`, `SecurityConfig`, `JwtAuthenticationFilter`.
- [ ] 8. Créer `InvitationService`, `UserService`.
- [ ] 9. Créer `AuthService` + DTOs + exceptions dédiées.
- [ ] 10. Créer `AuthController` + endpoints.
- [ ] 11. Créer `GlobalExceptionHandler` + `ApiError`.
- [ ] 12. Configurer CORS et Swagger (Bearer scheme).
- [ ] 13. Écrire la classe de base `AbstractIntegrationTest` avec Testcontainers.
- [ ] 14. Écrire les tests d'intégration listés §10.
- [ ] 15. Vérifier manuellement via Swagger UI : register → login → me → refresh → logout.
- [ ] 16. Commit final de la phase.

---

## 14. Livrables de la phase

- Tests verts : `./gradlew test` → 0 échec, ≥15 tests d'intégration sur l'auth.
- Documentation OpenAPI générée à `/v3/api-docs` avec les 5 endpoints auth.
- Profils `dev` et `prod` fonctionnels.
- Migrations Flyway reproductibles (base détruite/recréée → état identique).
- README / CLAUDE.md mis à jour avec les commandes Docker Compose et le flux de création d'un premier utilisateur admin.
