# Virtual Card Issuance Platform

A robust, scalable, and extensible backend service for issuing virtual cards, processing spend/top-up operations, and tracking transactions — built with Java 21 + Spring Boot 3 + JOOQ + PostgreSQL.

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
# IMPORTANT: JOOQ generates type-safe SQL classes from the schema DDL.
# This happens automatically during the compile phase.
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

The domain layer (`com.malta.card.domain`) is intentionally **free of infrastructure dependencies** — no JPA annotations, no JOOQ imports. `Card` and `Transaction` are plain Java records. `CardService` contains all business logic and depends only on repository _interfaces_.

This hexagonal (ports-and-adapters) structure means:
- Business rules are unit-testable without starting Spring or a database
- The persistence technology can be swapped without touching service code
- Future features (e.g. fraud rules, authorization hooks) slot in at the service layer

### Why JOOQ over JPA?

JOOQ was chosen for several reasons aligned with the requirements:

1. **Type-safe SQL** — JOOQ generates Java classes from the schema DDL (see `target/generated-sources/jooq`). Typos in table/column names become compile errors, not runtime surprises.
2. **Explicit control** — No hidden lazy-loading, no N+1 surprise queries. Every SQL statement is visible in the repository code.
3. **Pessimistic locking** — `SELECT ... FOR UPDATE` is expressed naturally: `dsl.selectFrom(CARDS).where(...).forUpdate()`. With JPA this would require `@Lock(PESSIMISTIC_WRITE)` and careful transaction boundary management.
4. **Complex queries** — JOOQ handles joins, CTEs, and window functions ergonomically. As the platform grows (e.g. balance aggregations, fraud pattern queries), JOOQ scales better than JPQL.

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
| Data access | JOOQ | Spring Data JPA | Type-safe SQL, explicit locking, no N+1 risk |
| Concurrency | `SELECT FOR UPDATE` | Optimistic locking with version column | Simpler failure model; no retry loops needed |
| Insufficient funds | Return DECLINED transaction | Throw HTTP 422 | Preserves audit trail; declines are business events not errors |
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

**JOOQ DDL-based code generation** — Used `jooq-meta-extensions` (`DDLDatabase`) to generate type-safe Java classes directly from the Flyway migration SQL files without needing a running database at build time. This keeps the build reproducible and CI-friendly. The generated classes live in `target/generated-sources/jooq/` and are produced automatically during `mvn compile`.

**Bucket4j token bucket** — The token bucket algorithm provides bursty-traffic tolerance (a client can use accumulated tokens in a burst) while enforcing a sustained rate limit. This is preferable to a fixed-window counter which can allow 2× the limit at window boundaries.

**Testcontainers JDBC URL** — Using `jdbc:tc:postgresql:16:///dbname` with the TC JDBC driver lets Spring Boot manage the container lifecycle automatically, without requiring `@Container` annotations on every test class.
