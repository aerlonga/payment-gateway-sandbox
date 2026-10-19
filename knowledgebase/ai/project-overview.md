# Visao Geral

## Proposito

**Payment Gateway Sandbox**: API de processamento de pagamentos (projeto de portfolio/estudo) que **orquestra** um gateway externo em modo sandbox (Stripe) por tras de uma abstracao de provider. Nao processa dinheiro real e nao e para producao. A fonte de verdade financeira e o provider; a API guarda o estado local, a auditoria e garante idempotencia.

O projeto existe para demonstrar: modelagem de dominio financeiro, integracao com API externa, design desacoplado (porta/adapter), arquitetura orientada a eventos (Kafka + outbox), resiliencia, teste de carga consciente do que mede e deploy na AWS. O roadmap completo esta no README (secao 10).

## Stack

| Camada | Tecnologia |
|--------|------------|
| Runtime | Java 21 / Spring Boot 4.1 |
| API | Spring Web, Bean Validation, Actuator, springdoc-openapi (Swagger UI) |
| Banco | PostgreSQL 16, Flyway (migrations em `src/main/resources/db/migration`) |
| Mensageria | Apache Kafka (KRaft, 1 broker) — ainda nao usado pelo codigo |
| Gateway | Stripe (`stripe-java`, sandbox) e `FakePaymentProvider` (teste de carga/automatizados) |
| Resiliencia | Resilience4j (circuit breaker no Stripe) |
| Testes | JUnit 5, Mockito, Testcontainers (Postgres) |
| Teste de carga | k6 (planejado, v4) |
| Infra local | Docker Compose (postgres, kafka, app) |
| Deploy alvo | AWS free tier (EC2 + RDS, Kafka em container) — planejado, v5 |

## Principios

- Dominio e casos de uso so conhecem a porta `PaymentProvider`; nunca importam classes do Stripe.
- Dinheiro sempre em **centavos** (`BIGINT`/`Long`), nunca `double`/`float`.
- Idempotencia de escrita garantida pelo **banco** (constraint `UNIQUE`), nao por verificacao em memoria.
- Todo evento de mudanca de estado e gravado em `payment_events` na mesma transacao (base do outbox).
- Segredos (chave do Stripe, senha do banco) so em `.env`, nunca no Git.
- Contexto de IA (`AGENTS.md` / `knowledgebase/`) **versionado no Git** — nao ignorar no `.gitignore`.

## Estado atual (resumo)

v1 em andamento: endpoints 4.1 a 4.6, `PaymentProvider` + Stripe + Fake, estorno parcial, idempotencia sob concorrencia. Faltam `clientSecret` na resposta, `paymentMethod`/`description` no request e testes de controller. Webhook, Kafka, k6 e deploy sao fases seguintes. Detalhe por item na secao 13 do README e em [[../features/index|Features]].
