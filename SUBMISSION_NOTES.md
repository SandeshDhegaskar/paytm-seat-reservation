# Submission notes

## What to send
1. Public GitHub repository URL.
2. Public Railway URL.
3. `README.md` and `WRITEUP.md` in the repository.
4. `burst/burst.py` command and output from the deployed URL.
5. `/actuator/prometheus` and `/health/ready` URLs for verification.

## Before sending
- Run `git log --oneline --decorate --all` and confirm the history is present.
- Run the service from a clean checkout with Docker.
- Create a show with at least 50 seats including `A12`.
- Run the hot-seat burst against Railway.
- Retry one successful request with the exact same idempotency key.
- Reuse that key with another seat and confirm `409 IDEMPOTENCY_CONFLICT`.
- Fire 10 parallel requests for one user and verify no more than 4 seats are confirmed.
- Cancel a reservation and verify its seats become available.
- Check `available + held + confirmed == total` before and after load.

## Interview point to remember
The key answer is not “I checked whether the seat was free.” The key answer is: **“The database transaction takes row locks on the requested seats in deterministic order and performs the state transition before commit; therefore the check and mutation are serialized by PostgreSQL.”**
