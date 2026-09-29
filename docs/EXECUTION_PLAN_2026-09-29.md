# Plano completo de correção e reposicionamento

Fonte obrigatória: `C:\Users\Gabriel\Downloads\auditoria-github-2026-09-29.md`.

Este documento é o ponto de retomada. Nenhum item pode ser declarado concluído apenas por inspeção, README ou CI verde. Para cada finding: confirmar no código atual, reproduzir quando possível, criar teste de failure mode, corrigir a causa, executar o teste e repetir a regressão relevante.

## Estado preservado ao interromper

- Último commit: `882fbee fix(authz): defer provider cancellation until authorized event`.
- F01 foi corrigido e validado com 44 testes de domínio, 3 de resiliência e 10 integrados, todos verdes.
- Há trabalho de F02 **não commitado** em três arquivos:
  - `PaymentGatewayClient.java`: estados `Submitting`/`Unknown`, fechamento de tentativa desconhecida e retomada com a mesma chave.
  - `ReconciliationService.java`: inclusão de pagamentos sem `providerPaymentId`.
  - `SessionServiceIT.java`: cenário em que o provider executa e encerra a conexão sem resposta.
- O primeiro RED do novo teste ocorreu porque Spring representou a conexão truncada como `RestClientException`, não `ResourceAccessException`. A expectativa foi corrigida sem afrouxar as asserções de estado, chave e efeito único.
- A segunda execução desse teste foi interrompida a pedido do usuário antes do resultado. Portanto F02 continua **IN PROGRESS / NOT VERIFIED**.
- Nenhum processo Maven de validação continua rodando.
- Nenhuma alteração deste trabalho foi enviada ao GitHub após `882fbee`.

## Gate A — corretude e segurança

### F01 — autorização antes de side effect — FIXED, regressão final pendente

- Manter provider fora do caminho HTTP antes da autorização.
- Manter transição local e `OrderCancelled` na mesma transação.
- Na regressão final, provar novamente: proprietário consegue cancelar; outro usuário recebe negativa; outro usuário causa zero evento de cancelamento e zero chamada externa; admin obedece à matriz permitida.
- Melhorar a evidência com provider spy no consumidor, embora a arquitetura já impeça acesso ao provider sem mensagem autorizada.

### F02 — pagamento retomável — IN PROGRESS

1. Terminar o teste interrompido e confirmar RED/GREEN real.
2. Verificar se `RestClientException` genérica deve ser retryable na política Resilience4j quando causada por erro de transporte; não repetir erro de parsing/contrato indiscriminadamente.
3. Modelar estados persistentes claros: não iniciado, submitting, resultado desconhecido, confirmado, falha permanente e atenção manual.
4. Reusar sempre `providerIdempotencyKey`; nunca criar outra cobrança apenas por falta de resposta.
5. Reusar ou fechar com segurança tentativa incompleta após crash.
6. Permitir reconciliação sem `providerPaymentId` por reenvio idempotente ou lookup suportado pelo contrato.
7. Impedir que redelivery seja reconhecido antes de existir obrigação local retomável.
8. Testar timeout, 5xx, 400 permanente, crash com tentativa aberta, resposta perdida, redelivery e duas retomadas concorrentes.
9. Confirmar que existe um único efeito remoto e que o pedido sai de `AwaitingPayment`.

### F03 — delivery retomável

1. Carregar `DeliveryRow` e a `DeliveryQuoteRow` persistida ao retomar.
2. Nunca dereferenciar quote nula em entrega existente.
3. Reusar a chave de idempotência da entrega.
4. Tratar quote expirada com nova quote e política explícita, sem criar entrega duplicada.
5. Adotar com segurança o recurso remoto em duplicate/conflict quando o contrato fornecer identidade verificável.
6. Recuperar entrega sem `providerDeliveryId` após resposta perdida/restart.
7. Testar persistência local seguida de crash, resposta perdida, duplicate delivery, quote expirada, provider 400/500 e duas retomadas concorrentes.

### F04 — domínio governando runtime

1. Criar appliers únicos de status para Payment e Delivery.
2. Usar as mesmas regras em resposta HTTP, webhook, reconciliação e consumer.
3. Aplicar matriz de transições e `occurredAt`/versão temporal.
4. Bloquear `Paid -> Pending`, `Delivered -> Requested` e aplicação de entrega concluída em pedido incompatível.
5. Eliminar atribuições arbitrárias de status nos Rows fora dos appliers.
6. Testar snapshots fora de ordem, webhook/reconciliação concorrentes, duplicatas e estados terminais.

### F05 — idempotência HTTP durável

1. Associar chave, hash e `orderId` na mesma transação do pedido/estoque/outbox.
2. Tornar a resposta reconstruível a partir do pedido persistido.
3. Não remover reserva se o pedido já commitou.
4. Definir expiração sem permitir novo efeito para chave já associada.
5. Testar crash exatamente após commit do pedido e antes de `complete`, restart, replay igual, payload divergente e concorrência com a mesma chave.

### F06 — consumer claim/dedup

1. Persistir claim antes do trabalho com status, owner/token, lease e tentativas.
2. Condicionar conclusão/falha ao token atual.
3. Retomar claim abandonado após lease.
4. Para efeito local, manter claim e efeito na mesma transação quando possível.
5. Para efeito remoto, depender de workflow persistente + chave idempotente e documentar at-least-once, sem prometer exactly-once.
6. Separar identidade de consumer por obrigação/evento.
7. Testar duas réplicas, crash após claim, crash após efeito, mensagem reaparecendo e unique race.

### F07 — webhook claim/lease

1. Adicionar owner/token, `lockedAt`/`lockedUntil`, próximo retry e classificação de erro à inbox.
2. Selecionar Received, Failed elegível e Processing com lease expirado.
3. Condicionar apply/finalização ao token.
4. Fazer polling e SQS disputarem o mesmo claim durável.
5. Definir quando SQS recebe ack; falha da inbox e DLQ da fila devem continuar conceitos distintos.
6. Implementar backoff persistido e caminho de atenção manual/dead state.
7. Testar duas instâncias, crash em Processing, lease expirado, stale owner, retry e DLQ.

### F13 — credenciais no Sistema-portaria

1. Clonar/abrir o estado atual e confirmar se `db.py` e `logic.py` ainda contêm credenciais fixas.
2. Não reproduzir valores em logs ou relatório.
3. Remover credenciais do HEAD, usar configuração por ambiente e criar `.env.example` apenas com placeholders.
4. Substituir comparação de senha fixa por mecanismo adequado ao contexto, com hash quando houver autenticação local.
5. Executar secret scan no repositório e nos demais repositórios relevantes.
6. Sinalizar rotação se houver qualquer possibilidade de credenciais reais.
7. Não reescrever histórico automaticamente; explicar impacto e pedir decisão antes desse passo destrutivo.

## Gate B — resiliência e operação

### F08 — reconciliação idempotente e multi-replica

- No-op/upsert para snapshot já aplicado; não violar unique em `reconcile-<updatedAt>`.
- Claim/particionamento ou locking por candidato antes da chamada.
- Conclusão com fencing token.
- Nunca engolir exceção; registrar resultado e métrica.
- Testar duas reconciliações simultâneas e snapshot repetido.

### F09 — falhas permanentes

- Classificar transient, permanent, configuration e manual intervention.
- Provider 400/422 não pode reaparecer eternamente em toda rodada.
- Definir compensação: rejeição terminal, cancelamento/refund ou atenção manual conforme obrigação.
- Manter 408/429/5xx/transporte sob orçamento de retry finito.

### F10 — eventos sem handler

- Separar evento informativo de comando obrigatório.
- Comando sem handler deve falhar no startup ou permanecer pendente/falhar visivelmente.
- Cobrir modos messaging on/off e cada wiring suportado.
- Remover o teste atual que considera `OrderPaid` sem handler como sucesso e substituí-lo pela obrigação correta.

### F11 — observabilidade operacional

- Gerar correlation ID e trace context na entrada; persistir em outbox; propagar por SQS; restaurar no consumer.
- Usar `RestClient.Builder` instrumentado do contexto.
- Criar spans de criação/reconciliação de pagamento e entrega, outbox, inbox e consumer.
- Logs estruturados com IDs, etapa, tentativa, resultado e classe de falha; nunca secrets/payload sensível.
- Métricas: backlog e idade do mais antigo para outbox/inbox; Processing vencido; pagamentos/entregas pendentes; retries/failures; duração/resultado de reconciliação; mensagens processadas/falhadas; estado do circuit breaker.
- Expor telemetria real do worker, que hoje é non-web, por endpoint próprio seguro ou push/export OTLP comprovado.
- Criar cenário operacional “03:00 pagamento travado” e provar que logs/métricas/traces identificam a etapa e próxima ação.

### F12 — Docker/Kubernetes

- PVC para PostgreSQL local se restart/persistência fizer parte da demonstração.
- Sequência verificável migration -> seed opcional -> API/worker.
- Health do worker baseado em progresso/staleness dos loops, não apenas processo vivo.
- Readiness/liveness, resources, securityContext, `/tmp` gravável com root filesystem readonly e graceful shutdown.
- Testar substituição de pod, restart durante trabalho, persistência e encerramento seguro.
- Manter escopo local e declarar imagens `:local`, LocalStack e secrets de exemplo como simulação.

### F14 — supply chain/CI

- Adicionar Maven Wrapper e validar checksum/distribuição.
- Pinar Actions por SHA.
- Reduzir permissões por job; `security-events: write` apenas onde aplicável.
- Dependency review em PR e gate de vulnerabilidades Maven diretas/transitivas.
- Scan de API, worker e simulator; preservar Trivy High/Critical.
- Executar E2E no CI com evidência de consumer/webhook, evitando reconciliação mascarar falha.
- Publicar artifacts úteis e distinguir artifact SARIF de alertas ativos no Security.
- Adicionar timeouts a jobs e reduzir dependências mutáveis (`apt-get`, imagens/tag OTEL).
- Verificar Dockerfiles, bases e imagens por digest após as alterações.

### F15 — lease/visibility timeout

- Owner/token/fencing para outbox; conclusão só pelo dono atual.
- Dimensionar batch para orçamento temporal ou processar com concorrência limitada.
- Renovar lease durante handler longo.
- Renovar visibility SQS durante trabalho longo e impedir que itens finais do lote expirem antes de começar.
- Testar handler >60s, lease expirado, worker antigo tentando concluir e redelivery.

### LOWs relevantes

- Alinhar postal code HTTP (12) e banco (10).
- Trocar parsing textual de JSON SQS por DTO/parser.
- Medir N+1/paginação de produtos antes de otimizar.
- Tornar rate limit distribuído ou documentar limite por instância/proxy.
- Restringir seed com senhas conhecidas a ambiente local explícito.
- Reformatar classes críticas para permitir review de transações e erros.

## Gate C — regressão e evidência

Executar, guardar comandos/resultados e marcar NOT VERIFIED quando impossível:

1. Build limpo com Java 25 e Maven Wrapper.
2. Unitários de domínio e runtime.
3. Integração com PostgreSQL e LocalStack.
4. Failure modes F01–F15, incluindo concorrência e restart.
5. E2E happy path e falhas: pagamento perdido, webhook ausente, consumer ativo, duplicate delivery, cancel/refund.
6. Provar quais componentes participaram por mensagens/inbox/outbox e telemetria; desativar reconciliação nos cenários que validam consumer/webhook.
7. Gitleaks em histórico, CodeQL, dependency review/gate e scan Maven.
8. Trivy em todas as imagens e filesystem.
9. Kubeconform/manifests e execução local de Kubernetes com restart/persistência.
10. Repetir testes após qualquer correção descoberta pela regressão.

## Comparação final Java vs .NET

Comparar com evidência executada: correctness, consistency, recovery, idempotency, messaging, concurrency, observability, testing, CI, security, operability e uso idiomático. Manter explícito onde .NET ainda for superior. Não alterar o .NET apenas para criar simetria.

## Gate D — GitHub e posicionamento, somente após A–C verdes

1. Auditar inventário atual de repositórios, visibilidade, descriptions, topics, branches, workflows, links e secrets.
2. Atualizar README do perfil em inglês natural: Backend Engineer; C#/.NET + Java/Spring Boot; sistemas transacionais, integrações e confiabilidade; PHP como experiência profissional real.
3. Separar claramente Professional Background de Portfolio/Engineering Projects.
4. Destacar decisões e evidências, sem claims de experiência profissional em Java/.NET/AWS/Kubernetes que o histórico não sustente.
5. Corrigir link/nome antigo `fullfillmentHub`.
6. Padronizar READMEs, descriptions e topics dos projetos fortes após o código final.
7. História coerente dos dois Fulfillment Hubs: mesmos problemas, dois ecossistemas, escolhas idiomáticas e diferenças honestas.
8. Avaliar LedgerLab e orders-shipping-api com seus limites; não tratar READMEs privados como código auditado.
9. Recomendar pins exatos: fulfillment-hub (.NET), fulfillment-hub-java, ledger-lab, orders-shipping-api e os dois próximos backends realmente verificáveis. Alteração manual se a integração não suportar pins.
10. Recomendar arquivamento/privacidade de itens fracos; nunca deletar automaticamente.
11. Atualizar SECURITY.md, LICENSE, `.gitignore`, env examples e Dependabot onde necessário.
12. Criar commits pequenos por tema e fazer push apenas depois de cada gate local.

## Entrega final obrigatória

- Tabela F01–F15: FIXED / ALREADY FIXED / NOT FIXED / NOT VERIFIED, com código, teste e execução.
- Novos findings.
- Testes adicionados e resultados executados.
- Estado final de CI e segurança.
- Comparação Java vs .NET sem empate forçado.
- README/perfil, pins, descriptions/topics e mudanças realizadas.
- Evidências de aderência a backend financeiro.
- Limitações e claims não comprovados.
- Lista de commits e propósito.
- Quinze perguntas difíceis de entrevista e respostas técnicas esperadas.
- Responder quais três evidências um Staff Backend Engineer de banco investigaria primeiro e qual fragilidade técnica ainda permanece.

## Definição de pronto

O trabalho termina somente quando código, testes, recuperação de falhas, CI, segurança, documentação e perfil dizem a mesma verdade. Itens sem execução ficam como NOT VERIFIED. Nenhum finding é fechado por README, intenção de design ou teste que não atravesse o caminho real.
