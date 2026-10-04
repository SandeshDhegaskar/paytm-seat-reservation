# API contract

## Authentication used by the exercise
The sample auth adapter accepts `Authorization: Bearer user:<user-id>` and `Authorization: Bearer admin`. In a production deployment this adapter is the boundary for JWT/JWKS verification. The reservation JSON never contains `user_id`.

## POST /shows
Admin only.

Request:
```json
{"name":"friday-night","seats":["A1","A2"],"price_paise":25000,"per_user_limit":4}
```

## POST /shows/{id}/reserve
Authenticated user.

Request:
```json
{"seats":["A1","A2"],"idempotency_key":"req-123"}
```

Returns 201 on confirmation. Domain declines are 409 with a JSON error such as:
```json
{"code":"SEAT_TAKEN","message":"Seat(s) already taken: A1","request_id":"..."}
```

The service uses all-or-nothing semantics for multi-seat requests.

## POST /reservations/{id}/cancel
Only the authenticated reservation owner can cancel. A cancelled reservation's seats become available again.

## GET /shows/{id}
Returns every seat and counts. Example:
```json
{"id":"...","name":"friday-night","price_paise":25000,"per_user_limit":4,"seats":[{"seat":"A1","status":"available"}],"counts":{"available":1}}
```

## Operational endpoints
- `/health/live`
- `/health/ready`
- `/actuator/health`
- `/actuator/prometheus`
