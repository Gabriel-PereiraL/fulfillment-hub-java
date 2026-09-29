# Checkpoint de continuidade — FulfillmentHub Java

Atualizado em 29/09/2026 às 17:14 (America/Sao_Paulo).

## Estado atual

A reimplementação está funcional, documentada, validada e publicada em `https://github.com/Gabriel-PereiraL/fulfillment-hub-java`.

Workspace: `C:\Users\Gabriel\Documents\Codex\2026-09-28\files-pasted-by-the-user-objetivo\outputs\fulfillment-hub-java`.

Fonte .NET preservada sem alterações: `C:\Users\Gabriel\Documents\Codex\2026-09-24\segui-sua-abordagem-deixei-de-lado\work\fulfillment-hub`, commit `dd2af04e71e624f0177bb500ee99b048d29e3a20`.

## Evidências verdes

- Build Java 25/Spring Boot 4.1.1/Maven 3.9.16: PASS.
- 44 testes de domínio + 3 de resiliência + 9 integrados = 56 PASS, zero falhas/ignorados.
- PostgreSQL 17.6 e LocalStack reais; quatro migrations e seed idempotente.
- Compose final saudável com imagens non-root baseadas em JRE 25 fixada por digest.
- E2E final PASS: order `92a740e1-a151-4ec4-85b8-bb7bbcaf98f4`, Created→AwaitingPayment→Paid→DeliveryRequested→Delivered; replay idempotente e mismatch 422.
- Fluxo separado de cancelamento e refund foi validado anteriormente.
- Prometheus autenticado respondeu 200; perfil opcional Grafana LGTM configurado.
- Kubeconform: 14 recursos válidos, nenhum erro.
- Gitleaks: nenhuma fuga.
- Trivy foi iniciado, mas o download local do banco Java de 929 MiB foi interrompido pela estimativa de rede >15 min; CI tem gate Trivy com exit-code 1 para High/Critical.

## Retomada exata

1. Conferir a primeira execução dos workflows `ci` e `codeql` no GitHub.
2. Corrigir e enviar qualquer achado High/Critical caso o gate Trivy encontre algum.
3. O repositório foi criado privado. Alterar visibilidade somente com instrução explícita do proprietário.

Não registrar tokens ou credenciais neste arquivo. Não alterar o checkout .NET.
