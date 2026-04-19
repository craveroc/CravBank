# API Specification — CravBank

Spécification de l'API REST exposée par le serveur CravBank à l'application Android.

## Conventions

- **Base URL** : `https://<host>/api/v1`
- **Format** : JSON uniquement (`Content-Type: application/json`)
- **Authentification** : `Authorization: Bearer <accessToken>` sur tous les endpoints sauf `/auth/register`, `/auth/login`, `/auth/refresh`, `/bank/callback`
- **Dates** : ISO-8601 UTC (`2026-04-20T14:30:00Z`)
- **Montants** : décimaux en string pour éviter les erreurs de virgule flottante (`"1234.56"`)
- **Pagination** : `?page=0&size=50` (query params), réponse enveloppée `{ content, page, size, totalElements, totalPages }`
- **IDs** : UUID v4

## Format d'erreur standard

```json
{
  "timestamp": "2026-04-20T14:30:00Z",
  "status": 400,
  "code": "VALIDATION_ERROR",
  "message": "Email already in use",
  "path": "/api/v1/auth/register",
  "details": [
    { "field": "email", "message": "must be a valid email" }
  ]
}
```

Codes métier typiques : `VALIDATION_ERROR`, `UNAUTHORIZED`, `FORBIDDEN`, `NOT_FOUND`, `CONFLICT`, `BANK_SESSION_EXPIRED`, `BANK_PROVIDER_ERROR`, `RATE_LIMIT_EXCEEDED`, `INTERNAL_ERROR`.

---

## 1. Authentification

### `POST /auth/register`

Inscription via code d'invitation.

**Request**
```json
{
  "email": "user@example.com",
  "password": "min8chars",
  "invitationCode": "ABCD-1234"
}
```

**Response 201**
```json
{
  "id": "uuid",
  "email": "user@example.com",
  "createdAt": "2026-04-20T14:30:00Z"
}
```

**Erreurs** : `409 CONFLICT` (email existant), `400 VALIDATION_ERROR` (code invalide/expiré).

---

### `POST /auth/login`

**Request**
```json
{
  "email": "user@example.com",
  "password": "..."
}
```

**Response 200**
```json
{
  "accessToken": "eyJhbGc...",
  "refreshToken": "eyJhbGc...",
  "expiresIn": 900,
  "tokenType": "Bearer"
}
```

**Erreurs** : `401 UNAUTHORIZED`.

---

### `POST /auth/refresh`

**Request**
```json
{ "refreshToken": "eyJhbGc..." }
```

**Response 200** : même format que `/login`.

---

### `POST /auth/logout`

Révoque le refresh token courant.

**Response 204** — No Content.

---

### `GET /auth/me`

**Response 200**
```json
{
  "id": "uuid",
  "email": "user@example.com",
  "createdAt": "2026-04-20T14:30:00Z"
}
```

---

## 2. Connexion bancaire

### `GET /bank/aspsps?country=FR`

Liste les banques disponibles (relayé depuis EnableBanking, cachée 24h).

**Response 200**
```json
[
  {
    "name": "BNP Paribas",
    "country": "FR",
    "logo": "https://.../bnp.png",
    "maxConsentValidity": 180
  }
]
```

---

### `POST /bank/connect`

Initie la connexion à une banque. Retourne une URL à ouvrir dans un Chrome Custom Tab.

**Request**
```json
{
  "aspspName": "BNP Paribas",
  "country": "FR",
  "validityDays": 90
}
```

**Response 201**
```json
{
  "pendingSessionId": "uuid",
  "authUrl": "https://auth.bank.fr/...",
  "expiresAt": "2026-04-20T14:45:00Z"
}
```

Le client Android ouvre `authUrl`. À la fin de l'authentification banque, l'utilisateur est redirigé vers un deep link `cravbank://bank-connected?pendingSessionId=<uuid>&status=ok`.

---

### `GET /bank/callback` *(interne, appelé par EnableBanking — non documenté pour Android)*

Reçoit `?code=...&state=<pendingSessionId>` depuis EnableBanking, échange contre une session, puis redirige vers le deep link Android.

---

### `GET /bank/sessions`

Liste les sessions bancaires de l'utilisateur.

**Response 200**
```json
[
  {
    "id": "uuid",
    "aspspName": "BNP Paribas",
    "country": "FR",
    "status": "ACTIVE",
    "validUntil": "2026-07-19T14:30:00Z",
    "createdAt": "2026-04-20T14:30:00Z",
    "lastSyncAt": "2026-04-20T14:35:00Z",
    "accountsCount": 3
  }
]
```

Statuts : `ACTIVE`, `NEEDS_RECONSENT`, `EXPIRED`, `REVOKED`.

---

### `GET /bank/sessions/{id}`

**Response 200** — même objet que ci-dessus + `accounts: [...]`.

---

### `DELETE /bank/sessions/{id}`

Supprime la session et toutes les données associées (comptes, transactions).

**Response 204**.

---

### `POST /bank/sessions/{id}/sync`

Déclenche un sync manuel. Non-bloquant — retourne un job ID.

**Response 202**
```json
{
  "jobId": "uuid",
  "status": "RUNNING",
  "startedAt": "2026-04-20T14:30:00Z"
}
```

---

### `GET /bank/sessions/{id}/sync/{jobId}`

**Response 200**
```json
{
  "jobId": "uuid",
  "status": "SUCCESS",
  "startedAt": "2026-04-20T14:30:00Z",
  "finishedAt": "2026-04-20T14:30:12Z",
  "newTransactions": 14,
  "errorMessage": null
}
```

Statuts : `RUNNING`, `SUCCESS`, `FAILED`.

---

## 3. Comptes

### `GET /accounts`

**Response 200**
```json
[
  {
    "id": "uuid",
    "sessionId": "uuid",
    "aspspName": "BNP Paribas",
    "name": "Compte courant",
    "iban": "FR76 **** **** **** 1234",
    "currency": "EUR",
    "balance": "1234.56",
    "balanceUpdatedAt": "2026-04-20T14:30:00Z"
  }
]
```

L'IBAN est masqué par défaut. Ajouter `?unmask=true` pour récupérer la valeur complète (déchiffrée côté serveur, journalisé).

---

### `GET /accounts/{id}`

**Response 200** — même objet.

---

## 4. Transactions

### `GET /transactions`

**Query params**
- `accountId` (optionnel) — filtre par compte
- `from`, `to` (optionnels) — plage de dates ISO
- `categoryId` (optionnel)
- `minAmount`, `maxAmount` (optionnels, en valeur absolue)
- `search` (optionnel) — recherche dans counterparty/description
- `page`, `size` (défaut 0 / 50)
- `sort` (défaut `bookingDate,desc`)

**Response 200**
```json
{
  "content": [
    {
      "id": "uuid",
      "accountId": "uuid",
      "bookingDate": "2026-04-19",
      "valueDate": "2026-04-19",
      "amount": "-45.20",
      "currency": "EUR",
      "counterpartyName": "SUPERMARCHE X",
      "description": "CB 19/04 SUPERMARCHE X",
      "category": {
        "id": "uuid",
        "name": "Alimentation",
        "color": "#4CAF50"
      }
    }
  ],
  "page": 0,
  "size": 50,
  "totalElements": 423,
  "totalPages": 9
}
```

---

### `GET /transactions/{id}`

**Response 200** — transaction complète.

---

### `PATCH /transactions/{id}`

Permet de reclasser manuellement une transaction.

**Request**
```json
{
  "categoryId": "uuid",
  "createRule": true
}
```

Si `createRule: true`, une règle basée sur `counterpartyName` est créée pour les futures transactions similaires.

**Response 200** — transaction mise à jour.

---

## 5. Catégories

### `GET /categories`

Retourne les catégories globales + celles de l'utilisateur.

**Response 200**
```json
[
  {
    "id": "uuid",
    "name": "Alimentation",
    "parentId": null,
    "icon": "cart",
    "color": "#4CAF50",
    "scope": "GLOBAL"
  }
]
```

Scope : `GLOBAL` ou `USER`.

---

### `POST /categories`

Crée une catégorie utilisateur.

**Request**
```json
{
  "name": "Abonnements",
  "parentId": null,
  "icon": "repeat",
  "color": "#2196F3"
}
```

**Response 201** — catégorie créée.

---

### `PATCH /categories/{id}`

Modifie une catégorie utilisateur. Les catégories globales ne sont pas modifiables.

---

### `DELETE /categories/{id}`

Supprime une catégorie utilisateur. Les transactions liées deviennent `category: null`.

**Response 204**.

---

### `GET /categories/rules`

Liste les règles de catégorisation de l'utilisateur.

**Response 200**
```json
[
  {
    "id": "uuid",
    "pattern": "SUPERMARCHE.*",
    "field": "COUNTERPARTY_NAME",
    "categoryId": "uuid",
    "priority": 10
  }
]
```

---

### `POST /categories/rules`

**Request**
```json
{
  "pattern": "NETFLIX.*",
  "field": "COUNTERPARTY_NAME",
  "categoryId": "uuid",
  "priority": 10
}
```

**Response 201**.

`field` ∈ `COUNTERPARTY_NAME`, `DESCRIPTION`.

---

### `DELETE /categories/rules/{id}`

**Response 204**.

---

### `POST /categories/rules/apply`

Réapplique toutes les règles aux transactions existantes (utile après création d'une nouvelle règle).

**Response 200**
```json
{ "updatedCount": 47 }
```

---

## 6. Statistiques

### `GET /stats/monthly`

Agrégats par mois.

**Query params** : `from=2026-01`, `to=2026-04`, `accountId` (optionnel).

**Response 200**
```json
[
  {
    "month": "2026-04",
    "income": "3200.00",
    "expense": "-1847.50",
    "net": "1352.50",
    "transactionCount": 52
  }
]
```

---

### `GET /stats/by-category`

**Query params** : `from`, `to`, `accountId` (optionnel), `type=EXPENSE|INCOME` (optionnel).

**Response 200**
```json
[
  {
    "category": {
      "id": "uuid",
      "name": "Alimentation",
      "color": "#4CAF50"
    },
    "total": "-423.80",
    "transactionCount": 18,
    "share": 0.229
  },
  {
    "category": null,
    "total": "-85.00",
    "transactionCount": 3,
    "share": 0.046
  }
]
```

`share` = proportion du total (somme = 1.0).

---

### `GET /stats/balance-history`

Évolution du solde total dans le temps.

**Query params** : `from`, `to`, `granularity=DAY|WEEK|MONTH` (défaut `DAY`).

**Response 200**
```json
[
  { "date": "2026-04-01", "balance": "4500.00" },
  { "date": "2026-04-02", "balance": "4455.80" }
]
```

---

## 7. Administration (à définir en phase ultérieure)

### `POST /admin/invitations` (rôle admin requis)

Génère un code d'invitation.

**Request**
```json
{ "expiresInDays": 7 }
```

**Response 201**
```json
{
  "code": "ABCD-1234",
  "expiresAt": "2026-04-27T14:30:00Z"
}
```

---

## 8. Health / monitoring

- `GET /actuator/health` — check public (up/down).
- `GET /actuator/info` — version, build time.
- `GET /actuator/metrics`, `/actuator/prometheus` — restreints à l'admin ou au réseau interne.

---

## 9. Deep links Android attendus

| Deep link | Déclenché quand | Payload |
|---|---|---|
| `cravbank://bank-connected?pendingSessionId=<id>&status=ok` | Succès consent banque | app appelle `GET /bank/sessions/{id}` résolu via pendingId |
| `cravbank://bank-connected?pendingSessionId=<id>&status=error&reason=...` | Échec consent | app affiche l'erreur |

---

## 10. Codes HTTP

| Code | Usage |
|---|---|
| 200 | OK |
| 201 | Created |
| 202 | Accepted (opération async démarrée) |
| 204 | No Content |
| 400 | Validation error |
| 401 | Token manquant/invalide |
| 403 | Accès interdit (ressource d'un autre user) |
| 404 | Ressource introuvable |
| 409 | Conflit (doublon) |
| 410 | Session bancaire expirée (nécessite reconsent) |
| 429 | Rate limit |
| 500 | Erreur serveur |
| 502 | Erreur provider EnableBanking |

---

## 11. Versioning

L'API est versionnée dans le path (`/api/v1`). Une évolution breaking introduira `/api/v2` en cohabitation pendant au moins 3 mois.