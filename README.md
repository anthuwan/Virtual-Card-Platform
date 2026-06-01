# Virtual Card Issuance Platform

A production-grade backend for issuing virtual cards, processing spend/top-up operations, and tracking transactions — built with Java 21 + Spring Boot 3 + Spring Data JPA + PostgreSQL.

---

## Quick Start

```bash
# Start PostgreSQL
brew services start postgresql@16
psql -U postgres -c "CREATE DATABASE virtualcard;"

# Run (dev profile — no JWT required)
SPRING_PROFILES_ACTIVE=dev mvn spring-boot:run
```

> **Note:** JWT OAuth2 authentication is fully implemented but disabled in the `dev` profile for ease of evaluation. No `Authorization` header is needed — all requests are automatically authenticated as `dev-user`. To enable JWT, remove `SPRING_PROFILES_ACTIVE=dev` and configure `JWT_ISSUER_URI`.

| URL | Description |
|-----|-------------|
| http://localhost:8080/swagger-ui.html | Swagger UI |
| http://localhost:8080/actuator/health | Health check |
| http://localhost:8080/actuator/prometheus | Metrics |

```bash
mvn test -Dtest="CardServiceTest"   # unit tests
mvn verify                           # full suite (requires Docker)
```

---

## API

All endpoints require `Authorization: Bearer <jwt>` outside the `dev` profile.

| Method | Path | Description |
|--------|------|-------------|
| `POST` | `/api/v1/cards` | Issue a new virtual card |
| `GET` | `/api/v1/cards` | List cards owned by the authenticated user |
| `GET` | `/api/v1/cards/{id}` | Get card details and balance |
| `PATCH` | `/api/v1/cards/{id}/activate` | Reactivate a blocked card |
| `PATCH` | `/api/v1/cards/{id}/block` | Block an active card |
| `PATCH` | `/api/v1/cards/{id}/close` | Permanently close a card |
| `POST` | `/api/v1/cards/{id}/spend` | Spend from a card |
| `POST` | `/api/v1/cards/{id}/top-up` | Top up a card |
| `GET` | `/api/v1/cards/{id}/transactions` | Transaction history |

**Idempotency** — `Idempotency-Key` is required on spend/top-up for safe retries. Reusing the same key with the same card, operation type, and amount returns the original transaction. Reusing it for a different operation returns `409 IDEMPOTENCY_CONFLICT`.

**Declined vs Error** — Insufficient funds returns HTTP 200 `status: DECLINED`, not an error. Preserved for audit.

### Auth Modes

For normal environments, configure `JWT_ISSUER_URI` to your identity provider. Spring validates bearer tokens against the issuer JWKS, and card ownership is enforced with the JWT `sub` claim.

In Swagger UI, click **Authorize** and paste a bearer token when running outside the `dev` profile. Token issuance stays with the identity provider; this service only validates tokens.

For local review, use `SPRING_PROFILES_ACTIVE=dev`. This disables external JWT discovery and injects a fixed authenticated principal, `dev-user`, so the API can be exercised without running Keycloak/Auth0/Cognito.

---

## Key Features

**Concurrency safety**
- `SELECT ... FOR UPDATE` (pessimistic lock) serialises concurrent spends at DB level
- `@Version` + `@Retryable` (optimistic lock) as defence-in-depth — retries on conflict up to 3×

**Guaranteed event delivery**
- Transactional outbox — events written to DB in same transaction as business op
- Scheduler polls pending events; Kafka publishing is isolated behind a placeholder publisher and can be swapped to `KafkaTemplate`

**Fault tolerance**
- Resilience4j circuit breaker on `FraudCheckService` — fail-open fallback if fraud service is down

**Security**
- JWT OAuth2 resource server — token validated against identity provider JWKS
- Card ownership enforced — `card.ownerId` must match JWT `sub` claim, else 403

**Caching**
- Caffeine cache on `getCard()`, TTL 5s — evicted on balance/status change
- Redis is the intended multi-instance upgrade by replacing the cache manager configuration

**Observability**
- Micrometer → Prometheus metrics (spend/topup counters, amount distribution p50/p95/p99)
- Structured SLF4J logging with MDC, dedicated `AUDIT` logger per transaction

**Rate limiting**
- Bucket4j token bucket — 100 req/min per IP, configurable in `application.yml`

---

## Design Decisions

| Decision | Choice | Reason |
|---|---|---|
| Data access | Spring Data JPA | Team alignment; `@Lock`, dirty checking, derived queries |
| Concurrency | Pessimistic + optimistic | DB-level serialisation + version check as defence-in-depth |
| Declined funds | HTTP 200 DECLINED | Business event, not an error; preserves audit trail |
| Event delivery | Transactional outbox | At-least-once delivery even on app crash |
| Fault tolerance | Circuit breaker | Fraud service outage never blocks spend |
| Auth | JWT + ownership check | Stateless; cardholders access only their own cards |
| Caching | Caffeine → Redis-ready | Zero infra now; one config change to scale |
| Rate limiting | Bucket4j | Burst-tolerant; Redis-backed for distributed deployments |
| Schema | Flyway | SQL-first, version-controlled |
| Monetary | `DECIMAL(19,4)` | No floating-point errors |

---

## Infrastructure Placeholders

The following are implemented as placeholders (log intent, no real infra needed):

- **Kafka** — `CardEventPublisher` logs what would publish to `card.spend/topup/created/status`
- **Fraud** — `FraudCheckService` always returns `false`; circuit breaker already wired
- **Notifications** — `NotificationService` logs spend/topup/block/expire alerts
- **Webhooks** — `WebhookDispatcher` logs what would POST to registered endpoints

Replace each with a real implementation — the interfaces and wiring are already in place.

---

## Trade-offs

- Rate limiter is in-process — not shared across instances. Fix: Redis-backed Bucket4j
- Kafka is a placeholder — replace `CardEventPublisher` with `KafkaTemplate`
- No transaction pagination — needs cursor-based pagination at scale
- No webhook registration API — `WebhookDispatcher` needs endpoint storage + delivery tracking

---

## Evolution to Microservices / Event-Driven Architecture

- Keep this service as the card ledger owner: card state, balance mutations, idempotency, and transaction history remain in one transactional boundary.
- Publish immutable card and transaction events from the outbox to Kafka, keyed by `cardId` for per-card ordering.
- Split non-critical workflows into consumers: notifications, webhook delivery, fraud/risk analytics, reporting, and customer communications.
- Move read-heavy screens to projections built from events, while the write API continues to enforce balance invariants through the relational database.
- Introduce service-to-service auth, consumer idempotency, replay tooling, and dead-letter topics before replacing the placeholder publisher with production Kafka.

---

## What I Would Do With More Time

- Replace in-process Bucket4j with Redis-backed for shared rate limiting across instances
- Replace `CardEventPublisher` placeholder with real `KafkaTemplate` and consumer group
- Add cursor-based pagination on `GET /api/v1/cards/{id}/transactions`
- Add webhook endpoint registration API with HMAC signature verification and delivery log
- Add cursor-based pagination to `GET /api/v1/cards` (currently returns full list)
- Expand test coverage: security layer tests, outbox retry tests, circuit breaker state tests

---

## Learning Strategy for New Libraries

**Resilience4j** — Read the Spring Boot 3 auto-configuration docs first to understand what `@CircuitBreaker` wires automatically vs. what needs explicit config. Then wrote a failing test against `FraudCheckService` to confirm the fallback triggered correctly before wiring the rest.

**ShedLock** — Reviewed the JDBC provider README to understand the `lock_until` / `locked_at` / `locked_by` column contract, then cross-checked `lockAtMostFor` vs `lockAtLeastFor` semantics against the multi-instance failure scenarios (JVM crash mid-job, clock skew). Chose `usingDbTime()` to eliminate clock-skew risk across JVM instances.

**Bucket4j** — Started with the token bucket algorithm docs to confirm it matches burst-then-drain behaviour needed for a financial API. Confirmed the in-process limitation early and documented the Redis upgrade path so it isn't a surprise at scale.

**Testcontainers** — Used the JUnit 5 `@Testcontainers` + `@Container` annotations for lifecycle management rather than managing the container manually. The PostgreSQL module auto-configures the JDBC URL via `@DynamicPropertySource`, so test config stays minimal.
