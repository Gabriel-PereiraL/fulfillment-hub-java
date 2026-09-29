# Deployment and operations

## Release order

1. Build immutable API, worker and simulator images from the same commit.
2. Run the Flyway migration job once.
3. Deploy API instances and wait for readiness.
4. Deploy the worker; verify heartbeat, outbox backlog and DLQs.
5. Smoke-test login, catalog, order creation and webhook authentication.

The local seed is for Compose demonstrations and must not run in production.

## Health

- `/health/live` only proves that the API process can serve requests.
- `/health/ready` checks PostgreSQL, because every authenticated request depends on session state there.
- Broker/provider failures do not make API pods unready; the outbox and reconciliation absorb them.

## Failure handling

- Outbox rows become `Failed` after five attempts and can be requeued through the Admin endpoint.
- SQS redrives a message after five receives. Investigate the DLQ before redrive.
- Webhook rows retain error and attempt information. Reconciliation re-reads the provider rather than trusting an old body.
- Never delete idempotency or processed-message rows as a first response to an incident.

## Kubernetes local validation

Build images with the `:local` names referenced by `k8s/base.yaml`, load them into the local cluster, then create the namespace/configuration, start dependencies, execute migration and seed, and finally inspect the deployments.

```powershell
kubectl apply -f k8s/base.yaml
kubectl apply -f k8s/local-dependencies.yaml
kubectl -n fulfillment-hub rollout status deployment/postgres --timeout=180s
kubectl -n fulfillment-hub rollout status deployment/localstack --timeout=180s
kubectl -n fulfillment-hub wait --for=condition=complete job/fulfillment-migrate --timeout=180s
kubectl apply -f k8s/seed-job.yaml
kubectl -n fulfillment-hub rollout status deployment/api --timeout=180s
kubectl -n fulfillment-hub rollout status deployment/worker --timeout=180s
```
