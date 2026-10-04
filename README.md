# Seat Reservation at Scale

Paytm Money Backend Engineering take-home exercise: a concurrency-safe JSON API for assigned-seat reservation.

## Design in one minute

- **Java 21 + Spring Boot 3.5 + MySQL 8.0 (InnoDB)**.
- **All-or-nothing** multi-seat requests.
- Seats are rows in `show_seats`; a reservation locks requested seats with `SELECT ... FOR UPDATE` in deterministic lexical order.
- An **InnoDB transaction** owns the complete decision: lock -> validate -> insert reservation -> mark seats -> persist idempotency key -> commit.
- Per-user concurrency is serialized only for the same `(show,user)` using a persistent `booking_locks` row and `SELECT ... FOR UPDATE`, so two users can reserve different seats concurrently.
- Idempotency is persisted in `idempotency_keys` with a SHA-256 request fingerprint. A second request with the same key is replayed; a different payload returns `409`.
- Cancellation locks the reservation and its seat mappings, returns the seats to `available`, and never resurrects a seat belonging to another reservation.
- Money is `BIGINT` paise throughout; no floating point.
- Prometheus: `/actuator/prometheus`. Health: `/health/live`, `/health/ready`.
- Structured JSON console logging and `X-Request-Id` correlation.

## API

### Create show (admin)
```bash
curl -X POST "$BASE/shows" -H 'Authorization: Bearer admin' -H 'Content-Type: application/json' \
  -d '{"name":"friday-night","seats":["A1","A2","A3","A12"],"price_paise":25000,"per_user_limit":4}'
```

### Reserve
```bash
curl -X POST "$BASE/shows/$SHOW_ID/reserve" -H 'Authorization: Bearer user:alice' -H 'Content-Type: application/json' \
  -d '{"seats":["A12"],"idempotency_key":"alice-a12-001"}'
```

Identity comes from `Authorization`; there is intentionally no `user_id` field in the reservation request.

### Cancel
```bash
curl -X POST "$BASE/reservations/$RESERVATION_ID/cancel" -H 'Authorization: Bearer user:alice'
```

### State
`GET /shows/{id}` returns every seat plus counts. The invariant is:
`available + held + confirmed == total_seats` (this implementation has no timed-held state, so `held` is always 0).

### Health
- `GET /health/live`: process liveness; no DB dependency.
- `GET /health/ready`: executes `SELECT 1`; fails if MySQL is unavailable.
- Spring Actuator health/probes are also enabled.

## Database schema
See `src/main/resources/schema.sql`. Core tables:

- `shows`: show configuration and price.
- `show_seats`: one row per seat and current state.
- `reservations`: reservation ownership, amount, lifecycle.
- `reservation_seats`: active reservation-to-seat mapping.
- `idempotency_keys`: unique `(show_id,user_id,idempotency_key)` plus request hash.
- `booking_locks`: one persistent lock row per `(show_id,user_id)` used only for transaction-scoped per-user serialization.

## Concurrency correctness

### Hot seat
500 users can lock `A12` concurrently. InnoDB grants the row lock to one transaction first. That transaction changes `available -> confirmed` before commit. Other transactions then observe `confirmed` and return `409 SEAT_TAKEN`. There is no read-then-write gap.

### Multiple seats / deadlock avoidance
The requested seat list is sorted before locking. Every reservation therefore locks seats in the same global order. This prevents two transactions requesting overlapping seat sets in opposite order from forming a lock cycle. The API uses **all-or-nothing** semantics: if any requested seat is unavailable, no seat is changed.

### Per-user limit
A `booking_locks(show_id,user_id)` row is created with `INSERT IGNORE`, then locked with `SELECT ... FOR UPDATE`. The lock is held until the current InnoDB transaction commits/rolls back. The service counts active reserved seats while holding that lock. Different users do not wait on this application-level lock.

### Idempotency
The first request stores the canonical sorted seat list fingerprint and reservation ID. A retry returns the original reservation. If the same key is sent with different seats, the fingerprint differs and the API returns `409 IDEMPOTENCY_CONFLICT`. The idempotency row is committed in the same DB transaction as the reservation, so a successful booking cannot exist without its key record.

## Local run

Requirements: Java 21, Maven 3.9+, Docker.

```bash
docker compose up -d db
mvn spring-boot:run
```

Or build a container:

```bash
mvn clean package
 docker compose up --build
```

## Railway deployment

Deploy the repository with the included `Dockerfile` and `railway.toml`. Add a MySQL 8 service/plugin. Configure the application with a JDBC datasource, for example:

```text
SPRING_DATASOURCE_URL=jdbc:mysql://${MYSQLHOST}:${MYSQLPORT}/${MYSQLDATABASE}?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC&connectionCollation=utf8mb4_0900_ai_ci
DB_USER=${MYSQLUSER}
DB_PASSWORD=${MYSQLPASSWORD}
```

Use the exact MySQL connection variables provided by your Railway MySQL service. The application does not depend on database-specific Railway variable names. The container listens on Railway's `PORT` and uses `/health/ready` as its health check.

## Burst test

The included script intentionally attacks one hot seat with many different users:

```bash
python3 burst/burst.py https://YOUR-RAILWAY-DOMAIN --create --requests 500 --seat A12
```

Or against an existing show:

```bash
python3 burst/burst.py https://YOUR-RAILWAY-DOMAIN --show-id YOUR_SHOW_ID --requests 500 --seat A12
```

Expected hot-seat distribution: **exactly 1 `201`, remaining requests `409 SEAT_TAKEN`, and 0 `5xx`**. The script also fetches the final show and checks the reconciliation invariant.

For a true 20,000-client run, use a load generator from a machine capable of opening that many connections; the service itself has no special-case for the burst.

## Metrics

Important metrics include:

- `reservations_confirmed_total`
- `reservations_declined_total{reason="seat-taken"}`
- `reservations_declined_total{reason="per-user-limit"}`
- `reservations_declined_total{reason="idempotent-replay"}`
- `seats_available{show_id="..."}`
- standard JVM/HTTP/process metrics from Actuator

## What I would page on at 2am

1. Any HTTP 5xx rate above a small threshold.
2. Readiness failures / DB connection pool exhaustion.
3. Reservation transaction latency and lock-wait latency.
4. Reconciliation mismatch (`available + confirmed != total`).
5. Unexpected growth in database deadlocks/timeouts.
6. A divergence between confirmed counter and persisted reservation count.

## Consistency vs availability

For seat ownership, **consistency wins**. During a database partition or unavailable primary, readiness fails and reservation writes are rejected rather than accepting a booking that cannot be durably serialized. A distributed seat system must not trade a double-sale for availability.

## AI usage disclosure

AI was used as an engineering copilot for project scaffolding, API/README drafting, test/burst-script generation, and review of concurrency edge cases. The final design decisions—MySQL/InnoDB row locking, deterministic lock order, transaction boundaries, idempotency storage/fingerprint, transaction-scoped per-user lock rows, cancellation semantics, and operational checks—were reviewed and are documented here so they can be explained and extended during the interview.

## Scope / next steps

For a production payment-grade version I would add real JWT/JWKS validation, an API gateway/rate limiting, distributed tracing, database migrations (Flyway), stronger schema/version management, connection-pool and lock metrics, audit events, and a payment authorization workflow with an outbox/saga instead of treating reservation confirmation as payment capture.

## Submission checklist

Before sending the recruiter the repository, replace the sample Railway URL in your email with your actual public URL and run the burst against that deployed URL. Also verify `/health/ready`, `/actuator/prometheus`, the create-show flow, hot-seat storm, idempotent retry, same-key/different-body conflict, per-user limit, and cancellation.

> Important: the auth adapter is intentionally a small exercise adapter. In the interview, be explicit that `Bearer user:alice` is a stand-in for a real verified identity token; the security property being demonstrated is that the reservation service never trusts a `user_id` supplied in JSON.
