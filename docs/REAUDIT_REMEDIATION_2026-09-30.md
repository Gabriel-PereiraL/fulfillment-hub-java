# Reaudit remediation — F32–F37

I closed the six Java findings from the 30 September reaudit with executable behavior rather than documentation-only claims.

- SQS consumption now distinguishes an acquired claim, a busy lease and an already completed message. I delete only after an owned completion or a proven terminal duplicate.
- Payment `Refunded` is accepted only from `Paid`. Delivery create, cancel and reconciliation use the timestamped provider policy.
- I recheck the order after the quote call, before persisting or creating a delivery, so cancellation during that window causes no remote create.
- Simulator refunds require an idempotency key, replay the same result and reject non-positive or excessive amounts.
- Outbox rows capture request correlation and W3C traceparent values and the SQS publisher/consumer carry that context.
- The concurrent webhook test now verifies the actual invariant: one processing attempt, a busy concurrent delivery left unacknowledged, and terminal redelivery acknowledged.

Validation: unit reactor green; 28 runtime integration tests green against PostgreSQL 17.6 and LocalStack from Maven/Temurin 25.

