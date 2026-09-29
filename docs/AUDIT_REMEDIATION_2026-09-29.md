# Audit remediation — 2026-09-29

This is the evidence ledger for the F01–F15 remediation. A finding is marked fixed only after a reproducing test and the relevant regression checks pass.

| Finding | Current status | Confirmed evidence | Remediation evidence |
|---|---|---|---|
| F01 — authorization before side effects | Fixed | `OrderService.cancel` called the delivery provider after checking only order state and before resource ownership. | Provider access was removed from the HTTP command. Cancellation now commits the authorized local transition and durable `OrderCancelled` event before provider work. `customerCannotCancelAnotherCustomersOrderOrScheduleProviderEffects` proves a different customer cannot change the order, restore stock, or schedule the only message that can reach providers. Runtime regression: 44 domain + 3 resilience + 10 integration tests passed. |
| F02 — resumable payment | Confirmed | A lost create response leaves no provider ID; reconciliation only queries rows that already have one. | Pending. |
| F03 — resumable delivery | Confirmed | An existing local delivery suppresses a new quote, but the later create path still dereferences the absent quote. | Pending. |
| F04 — domain governs runtime | Confirmed | Provider snapshots directly replace payment and delivery row state, allowing stale-state regression. | Pending. |
| F05 — durable HTTP idempotency | Confirmed | Order commit and idempotency completion use independent transactions, leaving an unrecoverable `InProgress` crash window. | Pending. |
| F06 — consumer claim/dedup | Confirmed | The business effect runs before the processed-message insert; concurrent workers can both execute it. | Pending. |
| F07 — webhook claim | Confirmed | `Processing` has no owner token or lease and completion does not validate ownership. | Pending. |
| F08 — reconciliation | Confirmed | Jobs have no multi-replica claim and swallow runtime failures. | Pending. |
| F09 — permanent failures | Under review | Retry classification must be traced across HTTP, SQS, outbox, and reconciliation. | Pending. |
| F10 — missing event handlers | Confirmed | Known event types without a handler are silently marked processed. | Pending. |
| F11 — observability | Confirmed | Reconciliation failures are swallowed and workflow/backlog evidence is incomplete. | Pending. |
| F12 — Kubernetes operations | Under review | Manifests and restart/persistence behavior still require execution evidence. | Pending. |
| F13 — fixed credentials in Sistema-portaria | Confirmed by audit, current HEAD verification pending | The audit observed database credentials in `db.py` and fixed login credentials in `logic.py`; their real-world validity was not verified. | Inspect current repository, remove from HEAD, replace with safe configuration/authentication, scan without reproducing values, and require rotation if potentially real. History rewrite requires a separate explicit decision. |
| F14 — supply chain | Partially fixed | Existing CI pins/scans require a complete re-audit across images and dependencies. | Tomcat/Jackson vulnerability upgrades and Trivy High/Critical gate already exist. |
| F15 — leases/visibility | Confirmed | Outbox lease completion has no ownership token; SQS visibility has no renewal for long work. | Pending. |

## Working order

1. F01–F07 and any newly found High/Critical issue.
2. F08–F15 operational findings.
3. Full unit, integration, concurrency, E2E, security and container regression.
4. GitHub profile and repository presentation only after the technical gates pass.
