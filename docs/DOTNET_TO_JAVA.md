# Do .NET para Java/Spring neste sistema

Este documento explica as mudanças de modelo mental exigidas pelo port. Os dois projetos resolvem os mesmos problemas, mas não compartilham mecanismos internos.

## 1. C# e Java

### Tipos, nullability e records

C# carrega nullability como metadado analisado pelo compilador. Java ainda trata referências anuláveis no sistema de tipos comum; Bean Validation valida bordas HTTP e o domínio rejeita `null` nos construtores. `Optional` é usado para retorno opcional, não como campo de entidade JPA nem parâmetro universal.

Records Java, como records C#, expressam DTOs e value objects imutáveis. Entidades Hibernate continuam classes porque precisam de construtor sem argumentos, identidade estável, mutação controlada e proxies. O port usa records para requests/responses e classes em `runtime` para rows persistidas.

### Exceptions, generics e collections

Java distingue checked e unchecked exceptions. Falhas de domínio e infraestrutura deste sistema são unchecked porque controllers, transações e políticas de retry precisam classificá-las em uma fronteira central. Não se criou uma cadeia de `throws` que apenas transportaria erro técnico.

Generics Java sofrem type erasure e não têm a mesma expressividade de reified generics. Consultas JPA recebem a classe do resultado explicitamente. Collections retornadas ao exterior são snapshots imutáveis quando possível; entidades mantêm coleções mutáveis somente dentro da unidade de trabalho.

### LINQ, Streams, annotations e reflection

Streams substituem bem projeções e filtros em memória, mas não são um provedor de consulta como LINQ. Uma lambda sobre `Stream` nunca vira SQL. As consultas importantes usam JPQL ou SQL explícito, principalmente locks, `SKIP LOCKED`, updates atômicos e índices parciais.

Annotations são metadados lidos pelo framework e muitas dependem de proxies. `@Transactional`, `@Scheduled`, `@Validated` e regras de segurança não são simples atributos declarativos: sua posição, visibilidade e ciclo do bean determinam se produzem efeito.

### Async/await e concorrência

O port usa MVC síncrono e threads virtuais não foram adicionadas sem necessidade. Operações assíncronas de negócio ficam em processos worker e SQS. Isso preserva a separação do original entre request HTTP e trabalho durável. Java não exige transformar cada chamada I/O em `CompletableFuture`; o modelo escolhido precisa ser coerente do controller ao driver.

### Packages, módulos, dependências e build

Namespaces C# e packages Java parecem semelhantes, mas Maven também define módulos compiláveis e dependências direcionais. `domain` não depende de Spring; `runtime` adapta persistência e provedores; os três executáveis dependem desses módulos. O reactor Maven substitui a solution e o `dependencyManagement` centraliza versões como um `Directory.Packages.props`.

## 2. ASP.NET Core e Spring Boot

| ASP.NET Core | Spring Boot neste projeto | Consequência |
|---|---|---|
| Minimal API/endpoint group | `@RestController` + `@RequestMapping` | Contratos ficam em métodos tipados e annotations. |
| container DI | ApplicationContext | Beans são singleton por padrão e devem ser stateless. |
| middleware | servlet filter/Security filter chain | Ordem é parte do contrato de segurança. |
| options pattern | `@ConfigurationProperties` record validado | Startup falha cedo para configuração inválida. |
| environment | profile + variáveis | `application.yml` define defaults seguros; segredo vem do ambiente. |
| hosted service | processo worker + `@Scheduled` | Falha e escala do worker não afetam a API. |
| health checks | endpoints próprios + Actuator | Liveness não consulta banco; readiness consulta. |

Validation acontece antes do método com Jakarta Validation. Erros conhecidos viram `ProblemDetail` no advice central. Exceções internas não expõem stack, SQL ou mensagem do provedor. Configuração de rota usa deny-by-default na `SecurityFilterChain`; liberar um novo endpoint exige decisão explícita.

## 3. EF Core e JPA/Hibernate

`DbContext` e `EntityManager` representam unidade de trabalho, identity map e change tracking, mas os detalhes divergem. Hibernate pode fazer dirty checking no flush; `flush()` não confirma a transação. O port força flush em pontos onde precisa detectar constraint/concorrência antes de continuar.

Lazy loading é evitado nas bordas. DTOs são montados dentro da transação e OSIV está desligado. Isso torna consultas adicionais visíveis e evita serialização disparando SQL. Coleções de itens e histórico usam carregamento planejado; telas de lista usam projeção leve.

Flyway SQL substitui EF migrations porque locks, JSONB, índices parciais e `SKIP LOCKED` são recursos PostgreSQL que merecem SQL revisável. A aplicação nunca usa `ddl-auto=update`; `validate` detecta drift.

Concorrência otimista usa `@Version bigint`. O original usa `xmin`. Ambos rejeitam write-write conflict, mas o token Java vive no schema e não permite compartilhar automaticamente o banco do executável .NET. Depois de uma exceção otimista, o port limpa a unidade de trabalho e recarrega tudo antes do retry.

Transações programáticas com `TransactionTemplate` são usadas onde a fronteira muda em runtime. Tentativa de provedor e identificador idempotente são confirmados em `REQUIRES_NEW` antes da chamada externa. A aplicação final da resposta participa da transação do consumer com seu marcador de deduplicação.

## 4. Segurança

Spring Security executa uma cadeia de filtros antes do controller. O Resource Server Nimbus aceita somente HS256, issuer/audience/tempo corretos e exige `sid`. Depois da assinatura, consulta usuário e sessão no PostgreSQL; banco indisponível falha fechado.

Claims de roles são convertidas para authorities. Rotas administrativas exigem Admin; leitura de pedido também aplica ownership no serviço. Essa segunda camada evita IDOR mesmo se uma regra de rota for alterada.

Refresh token é aleatório, persistido somente como SHA-256 e rotacionado a cada uso. Replay do token antigo revoga a família inteira, inclusive o vencedor de uma corrida. Alteração de senha incrementa a versão de segurança e invalida sessões.

O filtro HTTP limita corpo, adiciona correlation ID e `Cache-Control: no-store`. Forwarded headers ficam desabilitados por padrão; um proxy só deve ser habilitado com allow-list na borda. Webhooks verificam HMAC sobre os bytes brutos antes de parsear JSON.

## 5. Mensageria e consistência

O outbox é uma tabela escrita no mesmo commit da mudança de pedido. Um dispatcher faz claim curto com `FOR UPDATE SKIP LOCKED`, lease e lote limitado. Publicação falha volta a Pending com backoff; após cinco tentativas fica Failed e pode ser refileirada por Admin.

SQS Standard entrega at-least-once. O worker grava `(consumer, message_id)` e o efeito de negócio antes do ack. Falha deixa a mensagem invisível por backoff exponencial com jitter; a redrive policy move para DLQ após cinco recebimentos.

Há duas filas: eventos de domínio e webhooks inbound, cada uma com DLQ. A API primeiro confirma o webhook no PostgreSQL, depois publica o ponteiro. Se SQS estiver indisponível, o poller do inbox é a recuperação durável. Duplicatas são eliminadas pela constraint `(provider, provider_event_id)`.

## 6. Resiliência

Polly foi substituído por Resilience4j. Cada provedor tem retry exponencial com jitter, máximo de quatro tentativas totais, circuit breaker por janela e timeouts de conexão/leitura de cinco segundos. Apenas falhas de transporte e 408/429/500/502/503/504 são transitórias. Rejeições 400/401/403/404/409/422 não são repetidas cegamente.

Idempotency keys persistidas tornam seguro repetir create/refund depois de timeout. Reconciliação periódica consulta o estado remoto de pagamentos e entregas e também recupera pedidos Paid sem delivery. O circuit breaker impede avalanche durante indisponibilidade sustentada.

## 7. Observabilidade

Structured logging ECS substitui os templates estruturados do `ILogger`. Correlation ID entra pelo filtro e acompanha a resposta. Micrometer fornece métricas JVM, HTTP, Hikari e Spring Security; métricas de retry e estado do circuit breaker usam prefixo `fh.provider`. Prometheus fica em `/actuator/prometheus` sob autenticação administrativa.

Micrometer Tracing com bridge OpenTelemetry exporta OTLP. O profile Compose `observability` inicia `grafana/otel-lgtm` com Grafana, Tempo, Loki e Prometheus. A outbox preserva correlation e `traceparent`; a continuidade completa depende de propagar esses campos no envelope e criar span de consumer, diferença registrada na matriz quando não for equivalente.

## 8. Testes

xUnit Facts/Theories viraram testes JUnit Jupiter e parameterized tests quando o conjunto de dados é o aspecto central. Mockito só é apropriado para colaboração isolada; regras de domínio não precisam de mocks.

O equivalente a `WebApplicationFactory` varia: contexto Spring não-web para serviços, HTTP real contra a imagem no E2E e PostgreSQL/LocalStack reais para integração. H2 não substitui PostgreSQL porque não valida JSONB, índices parciais, locks ou `SKIP LOCKED`.

O script E2E comprova login, catálogo, criação, replay idempotente, mismatch 422 e convergência até Delivered. A suíte de concorrência disputa a última unidade; a suíte de mensageria comprova outbox → SQS → consumer e dedup persistente.

## 9. Configuração

`appsettings.json` + Options vira `application.yml` + environment variables + `@ConfigurationProperties`. Records de configuração recebem Bean Validation para falhar no startup. Profiles escolhem comportamento de processo, enquanto segredos nunca ficam em profile versionado.

Variáveis usam nomes de implantação (`DATABASE_URL`, `JWT_SIGNING_KEY`, `SQS_ENDPOINT`). O Compose fornece somente valores locais. Kubernetes separa ConfigMap e Secret, embora produção deva integrar um secret manager real.

## 10. Build e ecossistema

`dotnet restore/build/test` vira o lifecycle Maven. `pom.xml` raiz agrega módulos e BOMs; `mvn clean verify` executa compilação, unitários e gates. Failsafe executa `*IT` no profile de integração. O compiler usa Java 25, todos os warnings e `-Werror`.

O build oficial roda em Maven/Temurin fixado por digest para não depender da máquina. Docker empacota cada executável e roda como usuário sem privilégio. Dependabot atualiza Maven, Actions e Docker; CI também executa Gitleaks e Trivy.

## 11. Coisas que um desenvolvedor .NET provavelmente fará errado ao começar com Spring

Os erros abaixo apareceram ou seriam perigosos exatamente neste port:

- esperar que `@Transactional` em autochamada abra outra transação;
- manter lock de uma transação externa e iniciar `REQUIRES_NEW` sobre a mesma linha;
- confundir flush com commit antes de chamar o provedor;
- depender do nome de parâmetro Java em `@PathVariable` sem compilar com `-parameters`;
- deixar OSIV esconder N+1 durante serialização;
- reutilizar o persistence context depois de conflito otimista;
- usar Stream como se fosse LINQ traduzido para SQL;
- modelar entidades Hibernate como records;
- usar `BigDecimal.equals` sem considerar escala;
- colocar ack SQS em `finally`;
- marcar outbox como processada quando não existe handler registrado;
- validar HMAC depois de desserializar o JSON;
- aceitar JWT sem consultar a sessão revogável;
- adicionar retry para qualquer 4xx;
- iniciar migration automaticamente em todas as réplicas.

## 12. Java/Spring para entrevista

### Essencial

- Java records, sealed types, exceptions, generics, collections, Streams e `java.time`;
- Maven lifecycle, scopes, BOM e reactor multi-module;
- IoC, ciclo de bean, scopes, constructor injection e proxy AOP;
- MVC, controllers, validation, exception handling e filtros;
- Spring Security filter chain, JWT, authorities e method/route authorization;
- JPA entity lifecycle, persistence context, dirty checking, flush e transactions;
- JPQL, SQL nativo, fetch strategy, N+1 e optimistic locking;
- JUnit 5, assertions, parameterized tests e integração com banco real.

### Importante

- `@Transactional` propagation/isolation e limites de proxy;
- Flyway, PostgreSQL locking e índices;
- outbox/inbox, at-least-once, idempotência e DLQ;
- Resilience4j retry/circuit breaker/timeout e classificação de erro;
- Actuator, Micrometer, OpenTelemetry e structured logging;
- Docker multi-stage, Compose health checks e configuração por ambiente.

### Diferencial

- análise de crash windows entre banco, HTTP externo e broker;
- `FOR UPDATE SKIP LOCKED`, leases e backpressure;
- modelagem de reconciliação e webhooks fora de ordem;
- Testcontainers e testes determinísticos de concorrência;
- hardening Kubernetes, probes, resources e execução não-root;
- desenho de métricas de baixa cardinalidade e propagação de trace em mensagens.

| .NET | Java/Spring | Consequência prática |
|---|---|---|
| Minimal APIs | `@RestController` e records de transporte | Rotas, validação e autorização ficam explícitas em annotations/configuração. |
| DI scoped | beans stateless + proxy transacional | Entidades e dados de request nunca devem virar estado de singleton. |
| EF Core `DbContext` | JPA `EntityManager`/Hibernate | `flush` envia SQL; commit continua pertencendo à transação. |
| `SaveChangesInterceptor` | outbox escrita dentro do serviço transacional | Listener `AFTER_COMMIT` não serve para garantir atomicidade. |
| `xmin` | coluna `@Version` numérica | O schema Java é autônomo e a concorrência fica explícita/portável. |
| EF migrations | Flyway SQL | A migration roda como etapa operacional separada e o startup usa `validate`. |
| `BackgroundService` | `@Scheduled` em processo worker | Cada tick precisa limitar lote e abrir unidade de trabalho própria. |
| Polly | política de cliente HTTP e classificação explícita | Retry deve distinguir timeout/408/429/500/502/503/504 de rejeição permanente. |
| JwtBearer | Spring Security Resource Server/Nimbus | A assinatura do JWT é apenas uma etapa; a sessão continua sendo consultada no banco. |
| `PasswordHasher` | `PasswordEncoder` PBKDF2 | O formato de hash não é interoperável automaticamente. |
| `TimeProvider` | `java.time.Clock` | Tempo de domínio/teste permanece injetável. |
| `decimal` | `BigDecimal` | Escala e arredondamento precisam ser normalizados; `equals` considera a escala. |
| records C# | records Java | Records continuam adequados para valores/DTOs, mas não para entidades JPA mutáveis. |
| xUnit | JUnit Jupiter | O port preserva cenários e invariantes, não nomes de helpers. |
| `WebApplicationFactory` | contexto Spring e HTTP real | PostgreSQL e LocalStack continuam reais nos testes de integração. |
| `Activity` | Micrometer Observation/OpenTelemetry | Contexto precisa ser propagado explicitamente em filas e executores. |

## 15 aprendizados essenciais

1. Uma annotation `@Transactional` funciona por proxy; autochamada dentro do mesmo bean não cria nova fronteira.
2. `EntityManager` é uma unidade de trabalho, não um repositório global nem thread-safe.
3. Depois de conflito otimista, descarte a unidade de trabalho e tente novamente com estado recarregado.
4. `flush()` não significa commit.
5. Relacionamentos JPA lazy fora da transação falham; projete DTOs dentro da fronteira adequada.
6. `BigDecimal` deve ser criado a partir de texto para dinheiro e normalizado com política de arredondamento.
7. Records são excelentes contratos imutáveis; entidades JPA precisam de identidade e ciclo de vida controlados.
8. A ordem dos filtros Spring Security altera autenticação, limites e tratamento de erros.
9. JWT assinado não substitui revogação e estado de sessão quando o contrato exige logout imediato.
10. `Clock` evita testes frágeis e converte tempo em dependência explícita.
11. Flyway deve ser a fonte de verdade do schema; `ddl-auto=validate` detecta drift sem mutar produção.
12. Mensageria at-least-once exige efeito idempotente e inbox/`processed_messages`, não apenas retry.
13. Confirme uma mensagem somente depois do commit do efeito.
14. Webhook assinado ainda não prova um movimento financeiro; consulte o provedor para Paid/Refunded.
15. Observabilidade atravessa processos somente quando correlation e trace context viajam no envelope persistido.

## 10 erros comuns de quem vem de .NET

1. Colocar `@Transactional` em método privado e assumir que o proxy o interceptará.
2. Reutilizar uma entidade JPA após `OptimisticLockException`.
3. Usar `double` para dinheiro.
4. Habilitar `ddl-auto=update` em produção.
5. Tratar Spring singleton como serviço scoped com estado mutável.
6. Confirmar SQS antes do commit.
7. Implementar outbox com evento somente em memória.
8. Aceitar JWT pela assinatura quando PostgreSQL está indisponível.
9. Desserializar e reserializar antes de verificar HMAC do webhook.
10. Aplicar retry a todo 4xx/5xx e multiplicar efeitos permanentes.
