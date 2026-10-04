# WRITEUP — Seat Reservation at Scale

## Atomic decision
The authoritative decision is made inside one PostgreSQL transaction. Requested seat rows are selected with `FOR UPDATE` in deterministic seat-number order. The service then validates their current state and updates all seats from `available` to `confirmed` before committing. PostgreSQL row locks mean two transactions cannot both make the same seat available-to-confirmed decision.

This deliberately avoids `SELECT status` followed by an unlocked `UPDATE`, which would be a classic race.

For multi-seat reservations, requests are **all-or-nothing**. Seat identifiers are sorted before locking. Sorting creates a global lock order and prevents deadlocks caused by A12/A13 versus A13/A12 requests.

## Idempotency
`idempotency_keys` stores `(show_id, user_id, idempotency_key)` as the primary key, the SHA-256 fingerprint of the canonical request, and the reservation ID. The key and reservation are committed in the same transaction. A retry therefore returns the original reservation without changing seat state. Reusing the key with different seats returns `409 IDEMPOTENCY_CONFLICT`.

A second concurrent request with the same key rechecks the idempotency table after acquiring the per-user transaction lock. This closes the race where both requests initially observe no idempotency row.

## Per-user limit
The service uses `pg_advisory_xact_lock` on a deterministic hash of `user_id + show_id`. This is intentionally narrower than locking the whole show. Ten simultaneous requests from the same user therefore serialize the count-and-reserve decision, while requests from different users can proceed independently. The active seat count is then compared to `per_user_limit`.

## Holds / release
This implementation chooses an explicit cancellation model rather than a timed hold. `POST /reservations/{id}/cancel` is owner-only. The reservation row is locked first, then its seat mappings are locked and the seat state is returned to `available`. The mapping is deleted so the seat can be booked again. The original reservation remains as a cancelled audit record.

There is no path that can change a seat to available unless the reservation ID stored on that seat is the reservation being cancelled.

## Consistency vs availability
Seat ownership is a consistency-first problem. If PostgreSQL is unavailable or the primary cannot commit, the API should reject the write rather than acknowledge a booking that may be lost or duplicated. The readiness endpoint performs `SELECT 1` and fails closed when the database is unavailable.

## Observability
Every request gets an `X-Request-Id` and the application is configured for structured JSON console logging. Prometheus exposes confirmed reservations, declined reservations by domain reason, and available-seat gauges. The API's show state is the reconciliation source of truth.

The highest-value alerts are 5xx rate, readiness failures, DB pool exhaustion, transaction/lock latency, deadlocks/timeouts, and reconciliation mismatches.

## AI usage
AI was used for scaffolding, code review, documentation, and generating the concurrency burst client. I did not treat AI output as the correctness proof. The important concurrency decisions were explicitly reviewed against PostgreSQL transaction/locking semantics and are kept simple enough to explain and modify live.

## What I would do next
- Replace the exercise token adapter with JWT/JWKS validation.
- Add Flyway migrations and schema versioning.
- Add distributed tracing and DB lock-wait metrics.
- Add rate limiting/backpressure for on-sale bursts.
- Add an outbox for durable domain events.
- Separate reservation authorization from actual payment capture using an explicit payment workflow/saga.
- Run a 20k-client load test from multiple generators and tune DB connection pool, statement timeouts, and indexes using measured lock/latency data.
