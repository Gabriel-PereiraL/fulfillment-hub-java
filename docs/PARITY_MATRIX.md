# Matriz de paridade

Fonte funcional: `fulfillment-hub` .NET no commit `dd2af04e71e624f0177bb500ee99b048d29e3a20`.

| Área | Implementação Java | Evidência final |
|---|---|---|
| Domínio | Money, Product, Order, Payment, Delivery, User e Customer em Java puro | 44 testes unitários |
| Persistência | 18 tabelas, constraints, índices e quatro migrations Flyway | PostgreSQL 17.6 e `ddl-auto=validate` |
| Identidade | login, JWT, refresh rotativo, logout/revogação, senha, papéis e sessão autoritativa | 9 testes integrados no PostgreSQL |
| API | 17 operações `/api/v1`, ownership e Problem Details | startup real e E2E autenticado |
| Pedidos | reserva atômica, snapshots, cursor, cancelamento e histórico | última unidade concorrente e cancelamento testados |
| Idempotência HTTP | chave obrigatória, hash, replay byte a byte, mismatch e TTL | E2E confirmou replay e `422` |
| Outbox | mesmo commit, `SKIP LOCKED`, lease, retry, Failed e requeue | integração com concorrência/poison |
| SQS | filas Standard, DLQ, long polling, visibility backoff e máximo de cinco receives | LocalStack real no teste e no E2E |
| Deduplicação | marcador no mesmo commit do efeito e ack posterior | `processed_messages` integrado |
| Pagamento | intenção/tentativa durável, chave estável, create/get/refund e captura tardia | fluxo Paid e cancelamento→Refunded validados |
| Entrega | token, quote/create/get/cancel, eventos ordenados e reconciliação | E2E chegou a `Delivered` |
| Webhooks | 64 KiB, timestamp, HMAC raw constante, inbox única e processamento assíncrono | tamper/stale/dedup testados e fluxo real |
| Reconciliação | pagamentos pendentes, refunds, pedidos pagos e entregas pendentes | scheduler e E2E com fallback durável |
| Resiliência | timeouts, retry seletivo, circuit breaker e métricas | 3 testes: transitório, permanente e circuito aberto |
| Segurança | deny by default, papéis, ownership, no-store e imagens non-root | revisão, Gitleaks verde e E2E |
| Observabilidade | JSON/ECS, correlation, Prometheus, métricas provider e OTLP | endpoint Prometheus 200; perfil Grafana LGTM |
| Docker | API, worker e simulador em JRE 25 mínima; PG/LocalStack por digest | Compose saudável e E2E final verde |
| Kubernetes | API x2, worker, simulador, jobs, probes, limites e hardening | Kubeconform: 14 válidos, 0 inválidos |
| CI | build, integração, evidências, Gitleaks, Trivy e CodeQL | workflows versionados; execução remota ocorre após push |

## Resultado dos gates locais

- `clean verify`: 44 testes de domínio + 3 de resiliência.
- perfil `integration`: os 47 anteriores + 9 integrados = **56 testes**, zero falhas e zero ignorados.
- Compose: PostgreSQL, LocalStack, API, worker e simulador saudáveis.
- E2E final: pedido `92a740e1-a151-4ec4-85b8-bb7bbcaf98f4`, estado `Delivered`.
- Gitleaks 8.28.0: nenhuma fuga em aproximadamente 505 KiB.
- Kubeconform 0.7.0: 14 recursos válidos, nenhum inválido/erro/ignorado.
- Trivy local: download do banco Java de 929 MiB interrompido devido à estimativa de rede superior a 15 minutos; o job bloqueante permanece no GitHub Actions e ainda não executou por falta de publicação.

## Diferenças conscientes

- O Java usa `version bigint`/`@Version`; o .NET usa `xmin`. A garantia de concorrência foi preservada, mas o schema físico requer migração.
- Adaptadores Java geram UUID v4 onde partes do original usam UUIDv7. Nenhum contrato depende da ordenação temporal do UUID.
- PBKDF2 do Spring não compartilha o envelope do `PasswordHasher` ASP.NET; uma migração de usuários existentes exigiria verificador duplo temporário.
- O harness JUnit 5 abre o contexto diretamente porque Spring Test do Boot 4 usa APIs do JUnit 6. O requisito JUnit 5.14.4 foi mantido.
- O retry usa sua política própria para `429`; ainda não adapta dinamicamente o atraso ao header `Retry-After`.
- Correlação e `traceparent` são persistidos na outbox; a continuidade visual completa do trace depende da instrumentação do SDK/backend configurado.
