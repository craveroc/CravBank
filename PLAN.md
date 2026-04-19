# Plan d'implémentation — CravBank

## 1. Objectif

Serveur Spring Boot servant d'intermédiaire entre une application Android et l'API EnableBanking (PSD2). Il gère l'authentification utilisateur, les sessions bancaires, et maintient un cache local des comptes/transactions synchronisé périodiquement. Les données personnelles sensibles sont chiffrées au repos.

## 2. Décisions clés (validées)

| Choix | Décision |
|---|---|
| Utilisateurs | Multi-utilisateur privé (invitation manuelle) |
| Données exposées | Comptes, soldes, transactions, catégorisation, statistiques |
| Cache | PostgreSQL + sync périodique + refresh on-demand |
| Provider | EnableBanking uniquement (pas d'abstraction pour l'instant) |
| Chiffrement | Données personnelles et tokens bancaires chiffrés au repos |

## 3. Stack technique

- **Spring Boot 4.0.5 / Java 25 / Gradle** (déjà en place)
- **PostgreSQL** (prod) + **H2** (tests d'intégration)
- **Spring Data JPA** + **Flyway** pour les migrations
- **Spring Security** + **JWT** (access + refresh tokens)
- **BCrypt** pour les mots de passe utilisateur
- **AES-GCM** (via Jasypt ou implémentation maison avec `javax.crypto`) pour le chiffrement des tokens bancaires et PII
- **Spring `@Scheduled`** pour la synchronisation périodique
- **WebClient / RestClient** pour appeler l'API EnableBanking
- **SpringDoc OpenAPI** (déjà intégré) pour la documentation Swagger
- **JUnit 5** + **Testcontainers** (PostgreSQL) pour les tests d'intégration

## 4. Architecture logique

```
┌───────────────┐      ┌────────────────────────────────────┐      ┌──────────────┐
│  Android App  │◄────►│  CravBank Server (Spring Boot)     │◄────►│ EnableBanking│
└───────────────┘ JWT  │                                    │ JWS  └──────────────┘
                       │  ┌──────────────┐  ┌────────────┐  │
                       │  │ REST API     │  │ Scheduler  │  │
                       │  └──────┬───────┘  └──────┬─────┘  │
                       │         │                 │        │
                       │  ┌──────▼─────────────────▼─────┐  │
                       │  │ Services (Auth, Sync, Cat.)  │  │
                       │  └──────────────┬───────────────┘  │
                       │                 │                  │
                       │  ┌──────────────▼───────────────┐  │
                       │  │ JPA Repositories             │  │
                       │  └──────────────┬───────────────┘  │
                       │                 │                  │
                       │  ┌──────────────▼───────────────┐  │
                       │  │ PostgreSQL (chiffré au repos)│  │
                       │  └──────────────────────────────┘  │
                       └────────────────────────────────────┘
```

### Packages Java

```
com.cravero.cravbank
├── CravbankApplication.java
├── config/          # SecurityConfig, OpenApiConfig, WebClientConfig, SchedulingConfig
├── security/        # JwtFilter, JwtService, PasswordEncoder, CurrentUser
├── crypto/          # AesGcmEncryptor, EncryptedStringConverter (AttributeConverter JPA)
├── user/            # User entity + UserController + UserService + AuthController
├── bank/
│   ├── enablebanking/   # EnableBankingClient, DTOs API EnableBanking, signature JWS
│   ├── session/         # BankSession entity, SessionController, SessionService
│   ├── account/         # Account entity, AccountController, AccountService
│   ├── transaction/     # Transaction entity, TransactionController, TransactionService
│   └── sync/            # BankSyncService (scheduled), SyncJob entity
├── category/        # Category entity + rules engine + CategoryController
├── stats/           # StatsService + StatsController
└── common/          # ErrorHandler, ApiError, pagination utils
```

## 5. Modèle de données (tables principales)

```
users(id, email [unique], password_hash, created_at, invitation_id)
invitations(id, code, created_by, used_by, expires_at)

bank_sessions(id, user_id, provider, enablebanking_session_id [chiffré],
              aspsp_name, aspsp_country, valid_until, status, created_at)

accounts(id, user_id, session_id, enablebanking_account_id, iban [chiffré],
         name, currency, balance_amount, balance_updated_at)

transactions(id, account_id, enablebanking_tx_id [unique],
             booking_date, value_date, amount, currency,
             counterparty_name [chiffré], description [chiffré],
             category_id, raw_json [chiffré])
  index(account_id, booking_date desc)
  index(user_id via account, booking_date desc)

categories(id, user_id [nullable pour globales], name, parent_id, icon, color)

category_rules(id, user_id, pattern [regex sur description/counterparty],
               category_id, priority)

sync_jobs(id, user_id, session_id, started_at, finished_at, status, error_message)
```

### Chiffrement

- **Clé maîtresse** en variable d'environnement `CRAVBANK_MASTER_KEY` (32 bytes base64).
- Colonnes chiffrées via un `AttributeConverter` JPA (`EncryptedStringConverter`) utilisant AES-GCM avec IV aléatoire par ligne, stocké dans le même champ (`iv || ciphertext || tag`, base64).
- Pas de chiffrement au niveau colonne pour les données non sensibles (dates, montants agrégés, noms de comptes génériques) afin de pouvoir faire des requêtes/agrégations SQL.
- **À noter** : les montants/dates restent en clair pour permettre les stats ; si besoin plus strict, envisager Postgres `pgcrypto` ou chiffrement au niveau disque.

## 6. Flux d'authentification utilisateur (Android ↔ Serveur)

1. **Inscription sur invitation** : l'admin génère un code d'invitation → `POST /auth/register` avec `{email, password, invitationCode}` → création utilisateur.
2. **Login** : `POST /auth/login` → retourne `{accessToken (15 min), refreshToken (30 j)}`.
3. **Refresh** : `POST /auth/refresh` avec refresh token.
4. Tous les endpoints protégés exigent `Authorization: Bearer <accessToken>`.

## 7. Flux de connexion banque (EnableBanking)

EnableBanking utilise un flux PSD2 avec redirection vers la banque.

```
Android                   Server                    EnableBanking            Bank
   │                         │                            │                    │
   │ POST /bank/connect      │                            │                    │
   │ {aspsp, country}        │                            │                    │
   │───────────────────────►│                            │                    │
   │                         │ POST /auth (signé JWS)     │                    │
   │                         │───────────────────────────►│                    │
   │                         │◄─── {authUrl, sessionId} ──│                    │
   │◄── {authUrl, pendingId}─│                            │                    │
   │                         │                            │                    │
   │ Ouvre authUrl dans      │                            │                    │
   │ Chrome Custom Tab       │                            │                    │
   │─────────────────────────────────────────────────────►│────► authent. ────►│
   │                         │                            │                    │
   │                         │◄─── GET /callback?code=... (redirect user)      │
   │                         │ POST /sessions (échange)   │                    │
   │                         │───────────────────────────►│                    │
   │                         │◄──── sessionId final ──────│                    │
   │                         │ Redirige app:              │                    │
   │                         │ cravbank://bank-connected  │                    │
   │◄────────────────────────│ ?pendingId=...             │                    │
   │ GET /bank/sessions/{id} │                            │                    │
   │───────────────────────►│                            │                    │
   │◄── session + accounts ──│                            │                    │
```

**Points d'attention EnableBanking** :
- Les appels à l'API EnableBanking nécessitent une signature JWS (clé privée RSA + kid d'application).
- Les variables de config : `enablebanking.app-id`, `enablebanking.private-key-path`, `enablebanking.redirect-uri`, `enablebanking.base-url`.
- Une session EnableBanking a une durée de validité limitée (souvent 90 à 180 jours selon le pays/banque). Le serveur doit détecter les sessions expirées et notifier l'app Android pour re-consent.

## 8. Stratégie de synchronisation

- **Initiale** : à la création d'une session bancaire, on fait un sync complet (90 jours d'historique par défaut, configurable).
- **Périodique** : `@Scheduled` toutes les **6 heures** par défaut, configurable via `cravbank.sync.interval-minutes`. Récupère soldes + transactions depuis la dernière date connue.
- **On-demand** : `POST /bank/sessions/{id}/sync` force un sync immédiat (utile au pull-to-refresh Android).
- **Stratégie anti-doublons** : unicité sur `enablebanking_tx_id`.
- **Gestion d'erreur** : échecs consignés dans `sync_jobs`, retry exponentiel max 3 fois, puis session marquée `needs_reconsent` si 401/403.

## 9. Catégorisation

Approche simple en V1 :
1. **Catégories globales** pré-remplies (alimentation, transport, loisirs, revenus, logement, santé, autre).
2. **Règles utilisateur** : regex sur `counterparty_name` ou `description` → catégorie. Évaluées par ordre de priorité au moment du sync.
3. **Override manuel** : l'utilisateur peut reclasser une transaction (endpoint `PATCH /transactions/{id}`) ; ce choix crée optionnellement une règle auto.

V2 possible : classification ML (hors scope initial).

## 10. Statistiques

Endpoint `/stats/monthly` agrégé en SQL direct (PostgreSQL `date_trunc`). Pas de table de cache dédiée en V1 — les volumes restent petits (< 10k transactions par utilisateur).

## 11. Sécurité

- TLS obligatoire en prod (reverse proxy Caddy/Nginx).
- JWT signés HS256 avec `cravbank.jwt.secret` (32+ bytes).
- Password hashing BCrypt (coût 12).
- Chiffrement AES-GCM des PII bancaires (IBAN, noms, descriptions, tokens EnableBanking).
- Rate limiting basique (Bucket4j) sur `/auth/*`.
- CORS restrictif (app Android via User-Agent / pas d'origin Web).
- Logs : jamais logger les tokens EnableBanking ni les descriptions de transactions.

## 12. Phases d'implémentation

### Phase 1 — Fondations (infra + auth)
- Configuration Gradle : ajouter dépendances (JPA, Postgres, Security, Flyway, WebClient, Jasypt).
- Configuration `application.yml` avec profils `dev` / `prod`.
- Migrations Flyway V1 : tables `users`, `invitations`.
- `SecurityConfig` + JWT.
- Endpoints `/auth/register`, `/auth/login`, `/auth/refresh`.
- Tests d'intégration.

### Phase 2 — Chiffrement
- Implémenter `AesGcmEncryptor` + `EncryptedStringConverter`.
- Tests unitaires du converter.
- Gestion de la clé maîtresse par variable d'environnement.

### Phase 3 — Intégration EnableBanking
- Client HTTP avec signature JWS.
- `EnableBankingClient` avec méthodes `startAuth`, `exchangeCode`, `getAccounts`, `getBalances`, `getTransactions`.
- Migrations V2 : `bank_sessions`, `accounts`, `transactions`.
- Endpoints `/bank/connect`, `/bank/callback`, `/bank/sessions`.
- Deep link de retour vers l'app Android.

### Phase 4 — Synchronisation et cache
- `BankSyncService` avec sync initial + incrémental.
- `@Scheduled` tâche périodique.
- Endpoint `/bank/sessions/{id}/sync` (manuel).
- Migration V3 : `sync_jobs`.
- Gestion des sessions expirées.

### Phase 5 — Catégorisation
- Migration V4 : `categories`, `category_rules`.
- Seed des catégories globales (Flyway).
- Moteur de règles + application à la volée au sync.
- Endpoints CRUD catégories + règles + `PATCH /transactions/{id}`.

### Phase 6 — Statistiques
- Service d'agrégation (SQL).
- Endpoint `/stats/monthly`, `/stats/by-category`.

### Phase 7 — Polish
- Logs structurés (JSON).
- Métriques Actuator + Prometheus.
- Dockerfile et docker-compose (Postgres + app).
- Tests E2E avec un mock EnableBanking.

## 13. Déploiement

- **Production** : VPS Oracle Cloud (Free Tier ARM ou x86). Reverse proxy Caddy (TLS automatique via Let's Encrypt) + docker-compose (Postgres + app).
- **Dev local** : machine perso (Windows). Postgres via Docker Desktop. Le callback EnableBanking exige une URL publique HTTPS → utiliser **ngrok** (ou cloudflared tunnel) pendant le dev pour exposer `localhost:8080` avec une URL stable, à déclarer comme redirect URI côté EnableBanking.
- **Profils Spring** : `dev` (H2 optionnel, logs verbeux, ngrok) et `prod` (Postgres, logs structurés, TLS).
- **Secrets** : fichier `.env` non versionné en dev ; variables d'environnement systemd ou Oracle Vault en prod.

## 14. Questions ouvertes restantes

1. **Compte EnableBanking** : les credentials sandbox/prod sont-ils déjà obtenus ? Un app-id + clé privée sont nécessaires avant la phase 3.
2. **Re-consent** : comment l'app Android est-elle notifiée qu'une session a expiré ? (push notification FCM, ou simple erreur 410 au prochain appel ?)
3. **Multi-device** : un utilisateur peut-il se connecter depuis plusieurs appareils simultanément ? (impacte la gestion des refresh tokens)
4. **RGPD** : suppression de compte → quelles données supprimer vs anonymiser ?
5. **Backup DB** : stratégie de sauvegarde chiffrée (hors scope code mais à définir).