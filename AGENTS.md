# AGENTS.md

Este arquivo e a entrada principal para agentes de IA trabalhando neste repositorio.

## Projeto

**Payment Gateway Sandbox** e uma API de pagamentos em **Java 21 + Spring Boot 4** (projeto de portfolio/estudo) que orquestra um gateway externo em modo sandbox (Stripe) por tras da abstracao `PaymentProvider`, com PostgreSQL, Flyway, Kafka (planejado), Resilience4j, Swagger (springdoc) e teste de carga com k6 (planejado). **Nao processa dinheiro real.**

O ambiente de desenvolvimento roda em Docker Compose (Postgres + Kafka + app). O codigo fica em `src/main/java/com/aerlon/payment_gateway_sandbox`.

## Leitura Obrigatoria

Antes de alterar codigo, leia:

- [[knowledgebase/ai/index|Mapa da base de conhecimento]]
- [[knowledgebase/ai/project-overview|Visao geral]]
- [[knowledgebase/ai/architecture|Arquitetura]]
- [[knowledgebase/ai/decisions|Decisoes]]
- [[knowledgebase/ai/patterns|Padroes]]
- [[knowledgebase/ai/operations|Operacao]]
- [[knowledgebase/ai/environment|Ambiente]]
- [[knowledgebase/features/index|Contexto de features]]

Documentacao versionada de referencia:

- [README.md](README.md) — especificacao tecnica completa, roadmap (v1..v6) e status de implementacao (secao 13)
- [NOTAS.md](NOTAS.md) — notas de estudo e fluxo de teste pelo Swagger

## Arquitetura Atual

```
Cliente (Swagger / Postman / k6)
    -> PaymentController (/api/v1/payments)
    -> PaymentService
         -> Postgres (payments, payment_events)       [Flyway V1..V5]
         -> PaymentProvider (porta)
              -> FakePaymentProvider    (default; carga e testes)
              -> StripePaymentProvider  (sandbox; circuit breaker)

Planejado: webhook Stripe -> Kafka -> consumer (v2); outbox de payment_events (v3)
Docs: GET /swagger-ui.html, /v3/api-docs
```

Camadas principais (`com.aerlon.payment_gateway_sandbox`):

- **Apresentacao:** `api/` (controllers, DTOs em `record`, `GlobalExceptionHandler`)
- **Aplicacao:** `application/` (service, mapper e `port/` com a interface `PaymentProvider`)
- **Dominio:** `domain/` (entidades, enums, excecoes)
- **Infraestrutura:** `infrastructure/` (repositorios, adapters de provider `fake`/`stripe`)
- **Config:** `config/` (selecao de provider, OpenAPI)

## Comandos Principais

```bash
docker compose up -d --build        # postgres + kafka + app
docker compose up -d --build app    # depois de mudar codigo
docker compose down -v              # zera o banco
./mvnw test                         # testes (integracao exige Docker)
./mvnw clean test                   # se target/ corrompeu (erro de classe Lombok)
```

Rodar sem o container da app: suba `postgres` e `kafka` e use `./mvnw spring-boot:run` com o `.env` exportado (`set -a; source .env; set +a`). Detalhes em [[knowledgebase/ai/operations|Operacao]].

## Regras De Edicao

- Antes de implementar feature, fix ou chore, atualize a `hml` e crie uma nova branch a partir dela (`feat/...`, `fix/...`, `docs/...`).
- Nao implemente nem commite diretamente em `hml` ou `main`.
- **O agente faz commit e push da branch; o dono do projeto abre o PR e faz o merge** (`gh` nao esta instalado). Nao tente mergear. Depois do merge o dono da pull e avisa; so entao siga para o proximo passo.
- **Sem co-author em commits:** nunca adicione `Co-Authored-By` (nem variantes) nem atribuicao a agente em commits ou PRs, mesmo que a ferramenta sugira (ADR-008).
- Commits em portugues, sem acento, com prefixo convencional (`feat:`, `fix:`, `docs:`, `chore:`).
- **Dinheiro em centavos** (`Long`); nunca `double`/`float` (ADR-001).
- Dominio e services dependem so da porta `PaymentProvider`; nunca importar `com.stripe.*` fora de `infrastructure/provider/stripe` (ADR-002).
- Mudanca de status so pela maquina de estados do `PaymentService`; toda mudanca grava `PaymentEvent` na mesma transacao.
- **Migrations Flyway:** novo `V<n>__*.sql`; nunca editar migration ja aplicada.
- Controllers finos; regra de negocio em `application/service`. Endpoint novo ou contrato alterado: DTO `record` com validacao, `@Schema`/`@Operation`/`@ApiResponses` e teste.
- Ao mudar comportamento, adicione ou ajuste testes; teste de concorrencia/constraint usa Postgres real (Testcontainers).
- **Segredos** (`STRIPE_API_KEY`, senha do banco, CAs de rede) so em `.env`/arquivos locais ignorados. Nunca em codigo, log, doc ou commit; nao cole nem imprima chaves.
- Variavel de ambiente nova entra em `.env.example` (sem valor) e em `knowledgebase/ai/environment.md`.
- Nao desligue verificacao de certificado TLS para contornar rede corporativa; use o truststore local descrito em [[knowledgebase/ai/environment|Ambiente]].
- Ao tomar uma decisao arquitetural, registre em `knowledgebase/ai/decisions.md` (proximo ADR).
- Ao finalizar qualquer feature, fix ou chore, crie/atualize `knowledgebase/features/` (e a tabela de `features/index.md`).
- Se a mudanca alterar arquitetura, operacao, ambiente ou padroes, atualize tambem os arquivos correspondentes em `knowledgebase/ai/` e a tabela de status (secao 13) do `README.md`.
- **Contexto de IA versionado:** `AGENTS.md` e `knowledgebase/` **devem** ir para o Git. Nao adicione esses caminhos ao `.gitignore`.

## Regra De Contexto Por Entrega

Toda entrega deve deixar rastreavel:

- O que mudou.
- Por que mudou.
- Quais arquivos, comandos, variaveis ou fluxos foram afetados.
- Como validar.
- Quais pendencias ou riscos ficaram.

Use `knowledgebase/features/_template.md` para criar ou atualizar documentos de feature.

## Testes

Unitarios com Mockito (service e adapters) e de integracao com **Postgres real via Testcontainers** (`PostgresTestConfiguration`); o `contextLoads` tambem sobe Kafka. Os testes nao leem o `.env` e usam o provider `fake`.

```bash
./mvnw test
./mvnw test -Dtest='PaymentIdempotencyRaceTest,PaymentServiceRefundTest,StripePaymentProviderTest,FakePaymentProviderTest'
```

Antes de dar uma entrega como pronta, rode os testes afetados e, quando mexer em fluxo de pagamento, valide pelo Swagger/curl contra o container (ver `NOTAS.md`).
