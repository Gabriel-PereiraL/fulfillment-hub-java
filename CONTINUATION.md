# Checkpoint de continuidade — auditoria FulfillmentHub Java

Atualizado em 29/09/2026 (America/Sao_Paulo).

## Objetivo ativo

Corrigir e comprovar F01–F15, executar regressão total e somente então reposicionar o GitHub como portfólio de Backend Engineering em .NET e Java/Spring Boot.

Workspace: `C:\Users\Gabriel\Documents\Codex\2026-09-28\files-pasted-by-the-user-objetivo\outputs\fulfillment-hub-java`.

Fonte .NET preservada sem alterações: `C:\Users\Gabriel\Documents\Codex\2026-09-24\segui-sua-abordagem-deixei-de-lado\work\fulfillment-hub`, commit `dd2af04e71e624f0177bb500ee99b048d29e3a20`.

## Estado exato após pedido de parada

- A auditoria completa foi localizada em `C:\Users\Gabriel\Downloads\auditoria-github-2026-09-29.md` e lida.
- O plano integral de retomada está em `docs/EXECUTION_PLAN_2026-09-29.md`.
- F01 foi confirmado: `OrderService.cancel` chamava o provedor de entrega antes da autorização por recurso.
- F01 foi commitado em `882fbee`: a chamada externa direta foi removida do caminho HTTP. O cancelamento autorizado persiste `OrderCancelled`; o consumidor solicita refund e cancelamento da entrega.
- `customerCannotCancelAnotherCustomersOrderOrScheduleProviderEffects` prova que estado, estoque e outbox permanecem inalterados para pedido alheio.
- Regressão da etapa: 44 testes de domínio, 3 de resiliência e 10 integrados passaram; zero falhas.
- F02 concluído localmente: estados `Submitting`/`Unknown`, tentativa desconhecida encerrada e reconciliação de pagamentos sem provider ID usando a chave original.
- `paymentRecoversWhenProviderExecutesButEveryResponseIsLost` passou e provou uma única chave/efeito remoto e adoção do pagamento na retomada.
- Evidência da etapa em 30/09/2026: 44 testes de domínio, 3 de resiliência e 1 integração direcionada passaram.
- F03 concluído localmente: a retomada recarrega entrega/quote persistidas, renova quote expirada, mantém a chave e adota duplicate/conflict do provider.
- `deliveryRecoversPersistedQuoteAndAdoptsDuplicateAfterLostResponse` passou; houve uma entrega, uma quote e uma chave após resposta perdida.
- F04 concluído localmente com `ProviderStatePolicy`, usado pelos caminhos reais de pagamento e entrega.
- Cinco testes de política passaram; o teste integrado confirmou que Paid permanece Paid após snapshot Pending posterior.
- F05 concluído localmente com migration V5: chave e `orderId` são ligados na transação do pedido/estoque/outbox; replay recupera o pedido se `complete` não ocorreu.
- `committedOrderIsRecoveredWhenIdempotencyCompletionNeverRan` passou e provou um pedido, uma reserva de estoque e preservação da chave.
- F06/F07 concluídos localmente com migration V6: consumer e webhook fazem claim antes do efeito, usam owner/lease e conclusão condicionada ao token.
- O teste concorrente da inbox passou e comprovou um claim entre dois workers e retomada após lease expirado.
- F08: snapshots de delivery repetidos viram no-op sob lock; reconciliação deixou de engolir exceções silenciosamente.
- F10: evento obrigatório sem handler não é mais marcado Processed.
- F15: outbox ganhou owner/fencing, renovação antes do dispatch e batch máximo 10; heartbeat de visibility SQS ainda será verificado.
- F09: rejeição permanente de delivery vira estado terminal e dispara cancelamento/refund durável.
- F12: PostgreSQL local ganhou PVC; worker ganhou heartbeat, probes, `/tmp` gravável e graceful shutdown budget.
- F13: `Sistema-portaria` commit `5694124` remove credenciais fixas do HEAD e usa configuração/hashes; rotação histórica continua recomendada.
- F14: Maven Wrapper, Actions por SHA, timeouts, E2E no CI e scan Trivy das três imagens.
- F15: SQS processa uma mensagem por vez e amplia visibility para 300s; outbox mantém fencing.
- Próximo passo imediato: commits operacionais, regressão completa/E2E/scans, documentação final e GitHub.

## Evidência anterior preservada

Antes desta auditoria: 56 testes verdes, Compose/E2E entregue, Kubeconform 14/14, Gitleaks limpo, CodeQL verde e Trivy sem High/Critical após upgrades de Tomcat/Jackson. Essas evidências serão repetidas ao final; não são tratadas como prova das novas correções.

Nunca registrar tokens ou credenciais neste arquivo. Não reescrever histórico Git.
