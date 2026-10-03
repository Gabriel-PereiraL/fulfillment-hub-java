# Final engineering report — 2026-09-30

This report closes the audit remediation and GitHub positioning work without treating unexecuted checks as evidence.

## 1. Audit findings

| Finding | Result | Evidence |
|---|---|---|
| F01 authorization before side effects | FIXED | Cancellation authorizes ownership and commits `OrderCancelled` before provider work. The unauthorized-user integration test proves zero state, stock, outbox, or provider effect. |
| F02 resumable payment | FIXED | `Submitting`/`Unknown` states, stable key, and reconciliation without provider ID. Lost-response test proves one remote payment is adopted. |
| F03 resumable delivery | FIXED | Persisted quote/key reuse, expired-quote renewal, and safe conflict adoption. Lost-response integration test passes. |
| F04 domain governs runtime | FIXED | One `ProviderStatePolicy` protects payment and delivery paths. Unit and integration coverage rejects stale `Paid → Pending`. |
| F05 durable HTTP idempotency | FIXED | Migration V5 links key to order in the business transaction. Crash-window replay reconstructs the committed result without a second order or stock reservation. |
| F06 consumer claim/dedup | FIXED | Migration V6 adds owner/lease claims before work and owner-conditioned completion. The documented guarantee is at-least-once with idempotent workflows. |
| F07 webhook claim | FIXED | Durable owner/lease, expired-processing recovery, retry schedule, conditional completion, and SQS acknowledgement after completion. Concurrent claim test passes. |
| F08 reconciliation idempotency | FIXED | Delivery snapshots lock and no-op when already applied; job failures are logged rather than swallowed. |
| F09 permanent failure | FIXED | Permanent delivery 4xx becomes `FailedPermanent`, cancels the order, and emits durable compensation instead of retrying forever. |
| F10 missing handlers | FIXED | Required events without a handler fail and retry; they are no longer acknowledged as processed. |
| F11 observability | NOT FIXED IN FULL | Structured failures, correlation IDs, Prometheus, provider resilience metrics, OTLP configuration, and worker health exist. Complete trace restoration across outbox/SQS and backlog/oldest-age workflow gauges are not proved. |
| F12 Kubernetes operations | FIXED / RESTART NOT VERIFIED | PVC, worker heartbeat probes, writable `/tmp`, resources, restricted security context, and graceful shutdown are present. Manifest validation passed; a live pod replacement test was not run. |
| F13 fixed credentials | FIXED IN HEAD | `Sistema-portaria` now requires environment configuration and PBKDF2 hashes; Gitleaks is clean. Historical credentials may require rotation; history was not rewritten. |
| F14 supply chain | FIXED / REMOTE GATE PENDING | Wrapper, SHA-pinned Actions, permissions, timeouts, E2E, CodeQL/Gitleaks, and three-image Trivy matrix are configured. Final remote execution is recorded by GitHub Actions. |
| F15 leases and visibility | FIXED WITH BOUNDED WORK | Outbox owner/fencing and bounded batches; consumers extend SQS visibility to 300 seconds and process one message at a time. |

## 2. New findings

- The local Trivy container could not reliably scan Docker images in this Windows environment: shared-cache parallel scans corrupted the cache, daemon access was unavailable from the scanner container, and an exported-image scan stalled. This remains **NOT VERIFIED locally**; CI scans each of the three images independently.
- `Sistema-portaria` has no locally available Python runtime, so execution/syntax tests remain **NOT VERIFIED**. Its HEAD and history were secret-scanned.
- GitHub pins cannot be changed safely through the available repository CLI. The exact manual order appears below.

## 3. Tests

Final local regression:

- 44 domain tests passed.
- 8 runtime unit tests passed, including provider state policy and resilience.
- 14 PostgreSQL/LocalStack integration tests passed.
- Flyway applied and validated 6 migrations.
- Docker Compose built and reported API, worker, simulator, PostgreSQL, and LocalStack healthy.
- E2E order `935f07b5-6e33-4653-8037-c8c00384f92f` reached `Delivered` with real simulator payment and delivery identifiers.
- Kubeconform validated 15 of 15 Kubernetes resources.
- Gitleaks found no leaks in 18 Java commits or 4 `Sistema-portaria` commits.

The failure-mode suite covers authorization, lost provider response, idempotency crash window, stale state, duplicate delivery, concurrent webhook claims, abandoned lease recovery, missing handler, permanent rejection, retry, and circuit-breaker behavior.

## 4. CI and security

CI uses Maven Wrapper 3.9.16, immutable Action SHAs, minimal permissions, timeouts, integration/E2E jobs, CodeQL, Gitleaks, dependency review, and a Trivy matrix for API, worker, and provider simulator images. JWT validation, active sessions, deny-by-default authorization, signed raw-body webhooks, request limits, non-root images, and restricted Kubernetes contexts remain in place.

## 5. Java versus .NET

Both implementations now demonstrate the same core guarantees: atomic local writes, outbox messaging, idempotency, webhook authentication, provider recovery, reconciliation, and operational health. Java makes ownership/lease and provider-state policy explicit in its JPA runtime; .NET remains the older functional reference with broader historical maturity. The Java version has direct tests for the newly audited crash windows. A disposable Java demonstration was subsequently deployed and exercised on AWS on 2026-10-03; neither repository claims production-scale operation, global exactly-once delivery, or behavior under production-scale load.

## 6. GitHub profile

The profile now leads with Backend Engineering, C#/.NET, Java/Spring Boot, transactional systems, integrations, and reliability. PHP is explicitly presented as professional experience; .NET and Java projects are explicitly portfolio engineering work.

Recommended pin order:

1. `fulfillment-hub`
2. `fulfillment-hub-java`
3. `ledger-lab`
4. `Sistema-portaria`
5. `orders-shipping-api`
6. `Serraf-Delivery`

Repository descriptions and topics should reinforce the problem each project solves. `betterdev` should not occupy a portfolio pin while stronger executable backend evidence exists.

## 7. Financial-backend fit

The relevant evidence is concrete: atomic order/stock commits, idempotent request recovery, payment result uncertainty, stable external keys, duplicate and out-of-order message handling, owner-fenced processing, signed webhooks, reconciliation, durable compensation, PostgreSQL locking, and security/operability gates. These are the same categories of failure that matter in payment and ledger systems without claiming professional banking experience.

## 8. Limitations

- Full cross-process trace-context restoration and workflow backlog/age gauges remain incomplete.
- Container vulnerability results depend on the remote CI run because the final local three-image scan was inconclusive.
- Kubernetes restart/persistence behavior was manifest-validated but not exercised on a live cluster.
- No performance, soak, chaos, multi-region, high-availability, or production-scale AWS evidence exists. The Java repository has evidence from a single-instance AWS portfolio deployment dated 2026-10-03.
- `Sistema-portaria` needs credential rotation if the historical values were ever real, plus execution validation in a Python environment.
- GitHub pin order requires a manual UI change.

## 9. Commits

- `882fbee` — authorize cancellation before durable provider work.
- `f1f1635` — resume unknown payment creation.
- `cdccbe7` — resume persisted delivery requests.
- `c50ae17` — prevent stale provider-state regressions.
- `131cff0` — bind idempotency keys to committed orders.
- `8ffb0e2` — claim messages and webhooks before work.
- `16f4b72` — fence outbox work and reject missing handlers.
- `c1a52ca` — bound retries and harden local/runtime infrastructure.
- `5694124` (`Sistema-portaria`) — remove fixed credentials from HEAD.

## 10. Interview questions and expected answers

1. **Why is the system not exactly-once?** SQS and outbox delivery are at-least-once; stable business keys, claims, conditional completion, and idempotent state transitions absorb duplicates.
2. **What happens if the payment provider charges but the response is lost?** The attempt becomes Unknown and reconciliation retries with the same provider key, adopting the existing payment.
3. **Where is the HTTP idempotency crash window closed?** The reservation-to-order link commits with order, stock, and outbox; replay reconstructs the response.
4. **Can two workers process one webhook?** Only one valid owner token holds the lease; completion is conditional on that token and an expired lease is recoverable.
5. **What remains possible after a remote side effect and worker crash?** Redelivery is possible; remote duplication is constrained by stable provider keys, not by a distributed transaction.
6. **How are stale provider events handled?** A shared temporal/state policy accepts forward transitions and rejects regressions and incompatible terminal updates.
7. **Why use reconciliation as well as events?** Events provide timely progress; reconciliation repairs uncertain or lost outcomes without replacing the consumer path.
8. **How are retry storms limited?** HTTP retry handles short transient calls, SQS handles delivery, reconciliation handles uncertain workflow state, and each layer has bounded attempts/backoff.
9. **What does a permanent provider 400 do?** It enters a terminal failure, cancels the order, and schedules compensation instead of endless retry.
10. **How does cancellation authorization prevent external effects?** Ownership is checked before the transaction emits the only event that can invoke providers.
11. **What does the outbox guarantee?** Atomic persistence with business state and at-least-once dispatch; it does not guarantee a single remote execution.
12. **How would you diagnose a stuck payment at 03:00?** Use order/payment IDs and correlation logs, inspect outbox/inbox status and attempts, provider/circuit metrics, and reconciliation errors; missing end-to-end trace continuation is a known gap.
13. **Why PostgreSQL integration tests rather than H2?** The guarantees depend on real SQL constraints, locks, transactions, and Flyway migrations.
14. **What would you test before production?** Soak/chaos tests, broker interruption, pod replacement, lease timing, queue saturation, DB failover, provider latency, and operational alerts.
15. **What is idiomatic rather than copied from .NET?** Spring transactions/JPA, Micrometer, Resilience4j, Actuator, Maven lifecycle, and explicit Java runtime policies while preserving business contracts.

## 11. Final check

If a Staff Backend Engineer at a bank opens this GitHub now, the first three technical areas worth investigating are:

1. the payment lost-response recovery with a stable provider key;
2. the atomic idempotency/order/stock/outbox transaction and crash-window test;
3. the owner-fenced SQS/webhook/outbox processing model under duplicate delivery.

The largest remaining technical weakness is observability across asynchronous boundaries: the system persists identifiers and emits useful local telemetry, but it does not yet prove complete trace continuation and backlog-age signals from API through outbox, SQS, consumer, provider, and reconciliation.
