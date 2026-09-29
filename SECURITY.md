# Security policy

## Reporting

Do not open a public issue for a suspected vulnerability. Send the repository owner a private GitHub security advisory with reproduction steps, affected version and impact.

## Supported version

The `main` branch is supported. This repository is an engineering reference and does not publish long-lived release branches.

## Operational requirements

- Supply database, JWT, provider and webhook secrets through a secret manager.
- Use a random JWT HMAC key of at least 32 bytes and rotate it through a planned session invalidation.
- Terminate TLS before the API and retain HSTS.
- Keep forwarding headers disabled unless the trusted proxy ranges are configured explicitly.
- Restrict PostgreSQL, SQS endpoints, metrics and provider simulators to private networks.
- Run migrations as a dedicated deployment job before rolling out API/worker versions.
- Review Gitleaks, dependency and container scan output on every change.

Local credentials in Compose and Kubernetes examples are deliberately non-production values.
