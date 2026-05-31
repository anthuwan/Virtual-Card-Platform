# Virtual Card Issuance Platform

A robust, scalable, and extensible backend service for issuing virtual cards, processing spend/top-up operations, and tracking transactions — built with Java 21 + Spring Boot 3 + Spring Data JPA + PostgreSQL.

Designed with production-grade concerns in mind: pessimistic + optimistic locking for race condition safety, transactional outbox for guaranteed event delivery, Resilience4j circuit breaker for fault tolerance, Caffeine caching for read performance, and async event processing for Kafka, notifications, and webhooks.

---

## Quick Start

### Prerequisites
- Java 21+
- Maven 3.9+
- Docker & Docker Compose (for PostgreSQL)

### 1. Start the database

```bash
docker-compose up -d
```

### 2. Build and run

```bash
mvn spring-boot:run
```

The application starts on **http://localhost:8080**.

### 3. Explore the API

| URL | Description |
|-----|-------------|
| http://localhost:8080/swagger-ui.html | Interactive Swagger UI |
| http://localhost:8080/api-docs | OpenAPI 3.0 JSON spec |
| http://localhost:8080/actuator/health | Health check |
| http://localhost:8080/actuator/prometheus | Prometheus metrics |

### 4. Run tests

```bash
# Unit tests only (no Docker required)
mvn test -Dtest="CardServiceTest"

# Full suite including integration tests (requires Docker for Testcontainers)
mvn verify
```

---

## API Reference

| Method | Path | Description |
|--------|------|-------------|
| `POST` | `/api/v1/cards` | Issue a new virtual card |
| `GET` | `/api/v1/cards/{id}` | Retrieve card details and balance |
| `PATCH` | `/api/v1/cards/{id}/block` | Suspend an active card |
| `PATCH` | `/api/v1/cards/{id}/close` | Permanently close a card |
| `POST` | `/api/v1/cards/{id}/spend` | Deduct funds from a card |
| `POST` | `/api/v1/cards/{id}/top-up` | Add funds to a card |
| `GET` | `/api/v1/cards/{id}/transactions` | Retrieve transaction history |

### Idempotency

Mutating operations (`/spend`, `/top-up`) accept an optional `Idempotency-Key` header. Sending the same key twice returns the original response without re-processing — safe for client-side retries on network failures.

```bash
curl -X POST http://localhost:8080/api/v1/cards/{id}/spend \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: unique-client-key-001" \
  -d '{"amount": 25.00, "description": "Coffee shop"}'
```

### Declined vs. Error

A spend with insufficient funds returns **HTTP 200** with `"status": "DECLINED"`. This is an expected business outcome, not an error. The declined transaction is persisted for audit purposes.

---

## Architecture and Design Decisions

### Domain Model

The domain layer (`com.virtual.card.domain`) is structured around **Spring Data JPA**. `Card` and `Transaction` are JPA `@Entity` classes. `CardService` contains all business logic and depends only on repository interfaces (`JpaRepository`).

This hexagonal (ports-and-adapters) structure means:
- Business rules are unit-testable without starting a database (repositories are mocked)
- The persistence layer is cleanly separated from business logic
- Future features (e.g. fraud rules, authorization hooks) slot in at the service layer

### Data Access — Spring Data JPA

Spring Data JPA was chosen to align with the team's existing technology stack:

1. **Pessimistic locking** — `@Lock(LockModeType.PESSIMISTIC_WRITE)` on `findByIdForUpdate()` translates to `SELECT ... FOR UPDATE` in PostgreSQL — same serialisation guarantee as a raw SQL lock.
2. **Optimistic locking** — `@Version` on the `Card` entity provides defence-in-depth. If two concurrent requests slip past the pessimistic lock, only one UPDATE wins; the other gets `OptimisticLockException` and retries via `@Retryable`.
3. **Dirty checking** — Modifying a managed entity within a `@Transactional` method auto-saves on commit. No explicit `save()` needed for balance updates.
4. **Derived queries** — Spring Data generates boilerplate queries (`findById`, `existsById`) automatically, reducing repository code to only what's non-trivial.

### Concurrency Safety

Financial operations follow this sequence:

```
1. [service] Check idempotency key (fast path, no lock)
2. [DB]      SELECT ... FOR UPDATE on the card row      ← row lock acquired
3. [service] Validate card status
4. [service] Check balance sufficiency
5. [DB]      UPDATE cards SET balance = ...
6. [DB]      INSERT INTO transactions ...
7. [DB]      COMMIT                                      ← row lock released
```

`SELECT FOR UPDATE` ensures that two concurrent spends on the same card are serialised at the database level. One waits while the other completes. This prevents double-spend and lost-update anomalies without application-level locks.

The database schema also has a `CHECK (balance >= 0)` constraint as a defence-in-depth measure — if a bug bypasses the service-layer check, the DB will reject the update.

### Idempotency Implementation

Idempotency keys are stored in the `transactions` table with a partial unique index:

```sql
CREATE UNIQUE INDEX idx_transactions_idempotency_key
    ON transactions(idempotency_key) WHERE idempotency_key IS NOT NULL;
```

The service checks for an existing key _before_ acquiring the row lock (fast path). The DB unique index provides a safety net for the rare race where two requests with the same key arrive simultaneously — one will receive a duplicate key error, which can be caught and resolved by re-reading the existing record.

### Async Audit Trail

Transaction events are published via Spring's `ApplicationEventPublisher` after each spend/top-up. The `AuditEventListener` handles them on a separate thread pool (`@Async`), so audit logging never adds latency to the HTTP response path.

This is intentionally lightweight — no Kafka dependency for this iteration. The publisher side doesn't know or care about the listener implementation, so swapping to Kafka in the future is a listener-only change.

### Rate Limiting

`RateLimitFilter` uses **Bucket4j's token-bucket algorithm** to enforce per-IP limits (default: 100 requests/minute). Rate limit parameters are externalised to `application.yml` so they can be tuned per environment without code changes.

**Current limitation:** Buckets are stored in-process (`ConcurrentHashMap`). State is not shared across multiple instances. For a distributed deployment, replace the map with `Bucket4j + Redis` (first-class support in Bucket4j 8.x).

### Card Expiration

A `@Scheduled` job runs hourly (configurable via `app.card.expiry-check-cron`) and transitions ACTIVE cards past their `expires_at` to EXPIRED. The indexed query on `(status, expires_at)` keeps this efficient even at scale.

---

## Key Design Choices and Reasoning

| Decision | Chosen approach | Alternative considered | Reason |
|---|---|---|---|
| Data access | Spring Data JPA | Raw JDBC | Team alignment; `@Lock`, dirty checking, derived queries reduce boilerplate |
| Concurrency | Pessimistic + optimistic locking | Optimistic only | Pessimistic serialises at DB level; optimistic `@Version` + `@Retryable` as defence-in-depth |
| Insufficient funds | Return DECLINED transaction | Throw HTTP 422 | Preserves audit trail; declines are business events not errors |
| Event delivery | Transactional outbox | Fire-and-forget async | Guarantees at-least-once delivery even if app crashes after DB commit |
| Fault tolerance | Resilience4j circuit breaker | Timeout only | Prevents cascading failure when fraud service is slow or down |
| Caching | Caffeine (in-process, TTL 5s) | Redis | Zero infra for prototype; swap to Redis for multi-instance with one config change |
| Async audit | Spring Events | Kafka | No broker dependency; event bus is transparent to publisher |
| Rate limiting | Bucket4j in-process | Redis-backed distributed limiter | Sufficient for single-instance; easy to upgrade |
| Schema management | Flyway | Liquibase | SQL-first, no XML; easy to review in PRs |
| API versioning | URI-based (`/api/v1/`) | Header versioning | Simpler to test, cache, and route |
| Monetary precision | `DECIMAL(19,4)` | `BIGINT` cents | Self-documenting; avoids conversion bugs |

---

## Observability

### Metrics (Micrometer → Prometheus)

| Metric | Type | Description |
|--------|------|-------------|
| `card.created` | Counter | Total cards issued |
| `card.expired` | Counter | Cards expired by scheduler |
| `transaction.spend{outcome=success}` | Counter | Successful spends |
| `transaction.spend{outcome=declined}` | Counter | Declined spends |
| `transaction.topup{outcome=success}` | Counter | Successful top-ups |
| `transaction.amount` | Summary | Distribution of amounts (p50, p95, p99) |

Access at `/actuator/prometheus`. Wire to Grafana + Alertmanager for dashboards and alerts.

### Logging

Structured log entries at strategic points:
- Card created/expired
- Every spend/top-up (amount, outcome, new balance)
- Idempotent replay
- Rate limit violations
- All errors (with full stack trace at ERROR level)

A separate `AUDIT` logger emits one line per transaction for compliance purposes — ship this to a log aggregator.

---

## Trade-offs Made Under Time Constraints

1. **In-process rate limiting** — Bucket4j state is not shared across instances. A production deployment of N instances effectively allows N × 100 req/min per IP. Fix: Redis-backed Bucket4j.

2. **No authentication/authorisation** — The API is open. Production would require JWT validation and enforcement that users can only access their own cards.

3. **No pagination on transaction history** — `GET /transactions` returns all records. At scale (millions of transactions per card) this needs cursor-based pagination.

4. **Basic expiration only** — The scheduler marks cards EXPIRED but does not notify cardholders. A real system would send email/push notifications via an external service.

5. **Single-region** — The architecture is stateful (row locks, in-process rate limiter). Multi-region active-active would require significant rework.

---

## Potential Improvements With More Time

### Short-term
- Cursor-based pagination for transaction history
- Redis-backed Bucket4j for distributed rate limiting
- JWT authentication with per-user card access control
- Outbox pattern for guaranteed event delivery (vs. fire-and-forget Spring Events)
- ShedLock to prevent multiple instances running the expiration scheduler simultaneously

### Medium-term
- Kafka integration: publish transaction events to a topic; downstream services (fraud detection, notifications, reporting) subscribe independently
- Card-holder notification service triggered by card status changes
- Soft-delete + archival for closed cards (compliance/audit retention)
- Admin API for operations team (search cards, override status, etc.)

### Architectural evolution → Microservices

The current bounded contexts map naturally to services:

```
virtual-card-platform (current)
    ├── card-service          ← card issuance, lifecycle
    ├── transaction-service   ← spend, top-up, history
    ├── audit-service         ← consumes Kafka topic, stores audit records
    └── notification-service  ← consumes Kafka topic, sends emails/SMS
```

Decomposition strategy:
1. Extract the domain packages (`domain/card`, `domain/transaction`) into separate Spring Boot services
2. Replace direct method calls between services with Kafka event publishing
3. Each service owns its own database schema (no shared tables)
4. API gateway (e.g. Kong, Spring Cloud Gateway) handles routing and auth

### Event-driven spending flow (future)

```
POST /spend
  → card-service publishes SpendRequested event to Kafka
  → transaction-service consumes, validates, publishes SpendAuthorised or SpendDeclined
  → card-service consumes, updates balance
  → audit-service consumes, records audit entry
  → notification-service consumes, sends merchant receipt
```

This decouples authorisation from balance update, enabling async fraud screening without blocking the HTTP response.

---

## Learning Notes

**Spring Data JPA pessimistic locking** — `@Lock(LockModeType.PESSIMISTIC_WRITE)` on a repository method translates to `SELECT ... FOR UPDATE` in PostgreSQL. This serialises concurrent spend/top-up requests on the same card at the database level, preventing lost updates and double-spend conditions.

**Optimistic locking + @Retryable** — `@Version` on the `Card` entity adds a version counter incremented on every UPDATE. If two concurrent transactions read `version=N` and both attempt an update, only one succeeds — the other gets `OptimisticLockException`. `@Retryable` from Spring Retry transparently retries up to 3 times with exponential backoff, handling the conflict without surfacing an error to the caller.

**Transactional outbox** — Writing Kafka events to an `outbox_events` table in the same DB transaction as the business operation eliminates the gap between commit and publish. A scheduler polls PENDING events and publishes them, retrying up to 5 times before marking FAILED. This guarantees at-least-once delivery even if the app crashes mid-flight.

**Resilience4j circuit breaker** — The `@CircuitBreaker` annotation on `FraudCheckService` opens the circuit after 50% failure rate over 10 calls. The fallback method returns `false` (fail-open) so a slow or down fraud service never blocks legitimate spends.

**Bucket4j token bucket** — The token bucket algorithm provides bursty-traffic tolerance (a client can use accumulated tokens in a burst) while enforcing a sustained rate limit. This is preferable to a fixed-window counter which can allow 2× the limit at window boundaries.

**Testcontainers JDBC URL** — Using `jdbc:tc:postgresql:16:///dbname` with the TC JDBC driver lets Spring Boot manage the container lifecycle automatically, without requiring `@Container` annotations on every test class.
