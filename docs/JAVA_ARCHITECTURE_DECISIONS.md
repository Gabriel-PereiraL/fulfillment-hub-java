# Decisões arquiteturais Java

Este registro explica as decisões tomadas na reimplementação e as diferenças deliberadas em relação ao `fulfillment-hub` .NET no commit `dd2af04e71e624f0177bb500ee99b048d29e3a20`.

## ADR-001 — Java 25, Spring Boot 4 e Maven

**Contexto.** O projeto precisava de uma baseline moderna, reproduzível e suportada por containers.

**Abordagem .NET.** Solução multi-projeto em .NET, construída pelo SDK correspondente.

**Abordagem Java.** Java 25, Spring Boot 4.1.1 e Maven 3.9.16, com módulos Maven e imagens fixadas por digest.

**Por que mudou.** Essa é a estrutura convencional do ecossistema Java e evita simular conceitos do MSBuild.

**Trade-offs.** A equipe precisa conhecer o reactor Maven e acompanhar a compatibilidade de Java 25 com bibliotecas.

## ADR-002 — domínio sem dependências do Spring

**Contexto.** Regras e invariantes precisam permanecer testáveis sem infraestrutura.

**Abordagem .NET.** Entidades e value objects vivem em projeto de domínio separado.

**Abordagem Java.** O módulo `domain` usa Java puro; JPA, HTTP e transações ficam em `runtime`.

**Por que mudou.** A separação preserva a intenção original com limites idiomáticos para Maven/Spring.

**Trade-offs.** Existem mapeamentos explícitos entre agregados e entidades JPA.

## ADR-003 — PostgreSQL real e Flyway explícito

**Contexto.** Locks, índices parciais, JSONB e `SKIP LOCKED` não são reproduzidos com fidelidade por bancos em memória.

**Abordagem .NET.** PostgreSQL, migrations e testes de integração contra banco real.

**Abordagem Java.** Quatro migrations Flyway; `ddl-auto=validate`; migration e seed são comandos separados.

**Por que mudou.** Flyway fornece uma etapa operacional simples para os executáveis Java.

**Trade-offs.** Testes integrados dependem de Docker, mas exercitam o banco usado em produção.

## ADR-004 — `@Version` em coluna própria

**Contexto.** O original usa o system column PostgreSQL `xmin` para concorrência otimista.

**Abordagem .NET.** O provider do EF Core mapeia `xmin` como token de concorrência.

**Abordagem Java.** As entidades usam `version bigint` com `@Version`.

**Por que mudou.** É um contrato explícito, natural no JPA e mais fácil de inspecionar e migrar.

**Trade-offs.** Os schemas não são intercambiáveis sem migração, embora a garantia de exclusão otimista seja equivalente.

## ADR-005 — outbox no mesmo commit e dispatcher com lease

**Contexto.** Publicar depois do commit abre uma janela de perda; múltiplos workers não podem processar a mesma linha simultaneamente.

**Abordagem .NET.** Outbox transacional, claim concorrente, tentativas e requeue administrativo.

**Abordagem Java.** O evento é gravado junto do agregado; o claim usa `FOR UPDATE SKIP LOCKED`, lease de 60 segundos, cinco tentativas e equal jitter.

**Por que mudou.** Mantém a garantia original e usa primitivas nativas do PostgreSQL.

**Trade-offs.** O estado da entrega precisa de monitoramento e limpeza operacional.

## ADR-006 — SQS Standard com deduplicação no PostgreSQL

**Contexto.** SQS entrega pelo menos uma vez e pode repetir mensagens.

**Abordagem .NET.** Filas Standard, DLQ e marcador durável do consumidor no mesmo commit do efeito.

**Abordagem Java.** AWS SDK v2, filas e DLQs provisionadas no LocalStack, `processed_messages` antes do ack e ajuste de visibility com backoff.

**Por que mudou.** Preserva a topologia e permite executar localmente sem conta AWS.

**Trade-offs.** A deduplicação ocupa armazenamento e cada novo consumidor exige identidade estável.

## ADR-007 — inbox durável antes de aceitar webhooks

**Contexto.** O provedor precisa receber sucesso apenas quando o evento pode ser recuperado após uma falha.

**Abordagem .NET.** HMAC sobre bytes brutos, inbox única e processamento assíncrono.

**Abordagem Java.** O filtro limita o corpo, valida timestamp e HMAC em tempo constante; a API grava JSONB antes do `200`, publica um ponteiro UUID em SQS e o worker também faz polling de recuperação.

**Por que mudou.** A fila reduz latência e o polling fecha a janela entre commit e publicação.

**Trade-offs.** O mesmo evento pode ser acordado por dois caminhos; o claim transacional impede efeito duplicado.

## ADR-008 — sessão consultada em cada requisição autenticada

**Contexto.** Desativação, troca de senha e revogação precisam invalidar JWTs ainda não expirados.

**Abordagem .NET.** JWT inclui sessão e versão de segurança; o banco continua autoritativo.

**Abordagem Java.** Spring Resource Server/Nimbus valida o token e um filtro consulta usuário e sessão no PostgreSQL, falhando fechado.

**Por que mudou.** Usa os pontos de extensão do Spring Security sem enfraquecer o contrato.

**Trade-offs.** Cada requisição autenticada adiciona uma leitura ao banco.

## ADR-009 — rotação estrita de refresh token

**Contexto.** Dois refreshes concorrentes não podem gerar duas famílias válidas.

**Abordagem .NET.** O perdedor denuncia replay e revoga a família, inclusive o token emitido pela corrida vencedora.

**Abordagem Java.** Hash SHA-256 é persistido, a sessão é bloqueada e replay revoga a família em transação independente.

**Por que mudou.** A implementação traduz a garantia para locks JPA e limites transacionais Spring.

**Trade-offs.** Um cliente que dispara refreshes concorrentes precisa autenticar novamente.

## ADR-010 — persistir intenção antes de chamar provedores

**Contexto.** Timeout após uma chamada aceita não pode gerar outra cobrança ou outra entrega.

**Abordagem .NET.** Chaves idempotentes e external IDs sobrevivem a reinícios e reconciliação.

**Abordagem Java.** Pagamento, tentativa, cotação e entrega são gravados em `REQUIRES_NEW` antes da chamada; retries reutilizam as mesmas chaves.

**Por que mudou.** Separa claramente a durabilidade local da latência externa.

**Trade-offs.** Podem existir registros pendentes que exigem reconciliação, o que é intencional.

## ADR-011 — Resilience4j na borda HTTP

**Contexto.** Falhas transitórias devem ser repetidas; erros permanentes não; um provedor degradado não pode consumir todos os recursos.

**Abordagem .NET.** Policies de retry, timeout e circuit breaker nos clientes tipados.

**Abordagem Java.** Resilience4j aplica retry seletivo para transporte, 408, 429, 500, 502, 503 e 504; circuit breaker usa janela 20, mínimo 10, limiar 50% e abertura de 30 segundos. Clientes têm timeout de conexão e leitura de 5 segundos.

**Por que mudou.** Resilience4j é a biblioteca Java dedicada a esse problema e expõe estado para métricas.

**Trade-offs.** O backoff local não interpreta dinamicamente `Retry-After`; a reconciliação reduz o impacto dessa diferença.

## ADR-012 — três executáveis e configuração externa

**Contexto.** API, processamento assíncrono e simulador têm ciclos de vida e perfis de escala distintos.

**Abordagem .NET.** API, worker e ProviderSimulator são processos separados.

**Abordagem Java.** `api`, `worker` e `provider-simulator` geram jars/imagens próprios; `runtime` é biblioteca compartilhada.

**Por que mudou.** Mantém o limite operacional original e evita colocar jobs no processo web.

**Trade-offs.** Há mais artefatos para construir e implantar.

## ADR-013 — padrões abertos de observabilidade

**Contexto.** Fluxos atravessam HTTP, banco, outbox, SQS e webhooks.

**Abordagem .NET.** Logs estruturados, métricas e tracing distribuído.

**Abordagem Java.** Logback JSON/ECS, correlation ID, Micrometer/Prometheus, métricas de retry/circuito e OpenTelemetry via OTLP. Um perfil Compose opcional sobe Grafana LGTM.

**Por que mudou.** São integrações convencionais do Spring e não prendem o serviço a um fornecedor.

**Trade-offs.** Continuidade completa de trace através de mensagens persistidas depende do backend e da instrumentação do SDK; o correlation ID permanece como vínculo durável.
