# Adversarial audit remediation — checkpoint

Source: `C:\Users\Gabriel\Downloads\ADVERSARIAL_AUDIT_2026-09-30.md`, snapshot `9e0b7d3`.

The audit is treated as evidence to reproduce, not as executable instructions. Work order:

1. Java blockers F16–F20 and F31.
2. Java HTTP/inbox findings F21–F26.
3. Original residual findings F04/F05/F06/F07/F09/F11/F12/F14/F15.
4. Reproduce and repair inferred .NET F27/F28.
5. Reproduce and repair PHP F30.
6. Metadata F29, complete regression, scans, documentation, commit and push.

Current baseline: Java main `9e0b7d3`; official CI and CodeQL passed before this remediation. The audit reproduced six Java HIGH and six Java MEDIUM failures. No finding is marked fixed until its adversarial scenario and regression pass.

Required behavioral outcomes:

- A remote delivery result cannot resurrect a cancelled order.
- Delivery rejection/return/cancellation releases stock once and schedules refund/cancellation obligations durably.
- A stale permanent payment response cannot replace a settled payment.
- Provider simulator creation is atomic per idempotency key.
- Expired fifth webhook claims become recoverable/parked and `Ignored` remains acknowledgeable.
- Quote rejection converges to a terminal compensated state.
- Prometheus requires Admin.
- Invalid payload releases its idempotency reservation; concurrent first reservation never returns 500.
- Claims and documentation match the tested guarantees.

Never record credentials or tokens in this file. Do not rewrite Git history.

## Progress

- F16–F20: adversarial integration scenarios imported into the official suite and passing after runtime guards/compensation changes.
- F21/F22: expired fifth claim recovery and terminal `Ignored` redelivery pass.
- F23: permanent quote rejection now converges to cancelled with local compensation.
- F24: Prometheus is restricted to Admin.
- F25: address construction is inside reservation cleanup.
- F26: Hibernate persistence unique races are retried through the idempotency read path.
- F31: simulator creation uses atomic per-key compute; a 64-way payment and delivery concurrency test passes.

Evidence so far: full six-module build passes (44 domain + 8 runtime unit tests); imported 14-test adversarial PostgreSQL suite passes; simulator concurrency test passes. Full HTTP regression and final suite still pending.
