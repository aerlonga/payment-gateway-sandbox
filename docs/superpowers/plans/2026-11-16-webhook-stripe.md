# Webhook Stripe -> Kafka -> Consumer Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Receber eventos do Stripe (sandbox) por webhook assinado, enfileirar no Kafka e aplicar a mudanca de status do pagamento de forma idempotente.

**Architecture:** `POST /api/v1/webhooks/stripe` valida a assinatura, converte o evento Stripe num `ProviderEvent` neutro, publica no Kafka e responde 200 sem processar. Um consumer grava o `stripe_event_id` em `processed_webhook_events` e aplica a transicao via maquina de estados do `PaymentService`, tudo na mesma transacao. Todo uso de `com.stripe.*` fica em `infrastructure/provider/stripe` (ADR-002).

**Tech Stack:** Java 21, Spring Boot 4.1.1, spring-kafka, stripe-java 33.4.0, Postgres + Flyway (tabela V3 ja existe), Testcontainers (Postgres e Kafka).

**Spec:** `README.md` secao 4.7 e 6 (webhook, idempotencia de consumo), `knowledgebase/ai/decisions.md` ADR-002, ADR-005 e ADR-006.

## Global Constraints

- Branch `feat/webhook-stripe`, criada a partir de `hml` atualizada. Nada direto em `hml`/`main`.
- Commits em portugues, sem acento, prefixo convencional. **Sem `Co-Authored-By` nem atribuicao a agente** (ADR-008; vale mesmo que a ferramenta sugira). O agente faz commit e push; o dono abre o PR e faz o merge.
- `com.stripe.*` so em `infrastructure/provider/stripe`. `api/` e `application/` conhecem apenas tipos neutros.
- Mudanca de status so pela maquina de estados do `PaymentService`; toda mudanca grava `PaymentEvent` na mesma transacao.
- Dinheiro em centavos (`Long`). Migration nova so como `V<n>__*.sql`; **nao editar V3** (ja aplicada). A tabela `processed_webhook_events` ja existe.
- Controller fino; regra em `application/service`. Endpoint novo: DTO/validacao, `@Operation`/`@ApiResponses` e teste.
- Variavel nova entra em `.env.example` (**sem valor**) e em `knowledgebase/ai/environment.md`.
- Ao fechar: feature doc em `knowledgebase/features/` (+ tabela do `index.md`), ADR-010 em `decisions.md`, status 4.7 no `README.md`.

## Seguranca de segredos (vale para todas as tasks)

- Este plano, o codigo, os testes, a doc, os logs e os commits **nao contem nenhum segredo**. O segredo de assinatura do webhook entra so por variavel de ambiente `STRIPE_WEBHOOK_SECRET`, lida em `payment.stripe.webhook-secret` (`${STRIPE_WEBHOOK_SECRET:}`, **sem default**).
- Valor real fica so no `.env` local (ja no `.gitignore`). `.env.example` leva o nome da variavel vazio. `docker-compose.yml` repassa `${STRIPE_WEBHOOK_SECRET:-}`, nunca um literal.
- Testes usam um segredo claramente falso definido no proprio teste (`"test-only-signing-secret"`), sem o prefixo de chave real, para nao disparar scanners nem parecer credencial.
- Nunca logar o segredo, o header `Stripe-Signature` nem o corpo bruto do webhook. Logar so `eventId`, `type` e `paymentId`.
- Antes de **cada commit** rodar o guard abaixo; qualquer saida bloqueia o commit:
  ```bash
  git diff --cached -U0 | grep -nE "whsec_[A-Za-z0-9]+|sk_(test|live)_[A-Za-z0-9]+|pk_(test|live)_[A-Za-z0-9]+|rk_(test|live)_[A-Za-z0-9]+" && echo "SEGREDO NO DIFF" || echo ok
  ```
- Antes do push: `git ls-files | xargs grep -lE "whsec_[A-Za-z0-9]{8,}|sk_(test|live)_[A-Za-z0-9]{8,}"` deve sair vazio, e `git check-ignore .env` deve imprimir `.env`.
- Pendencia do dono (fora do codigo): ativar *Secret scanning* e *Push protection* nas configuracoes do repositorio no GitHub. No deploy (AWS), o segredo vai em gerenciador de segredos/variavel do ambiente, nunca no repositorio nem na imagem.
- O segredo do `stripe listen` (impresso no terminal) vai direto para o `.env`; nao colar em chat, issue, PR ou doc.

## Review Focus

- Webhook chega **antes** do commit do `POST /payments` (pagamento confirmado na criacao): o consumer nao acha o pagamento e deve tentar de novo, depois ir para a DLT, sem derrubar o consumer. Teste na Task 3.
- Mesmo evento entregue duas vezes (ou em duas threads): uma unica transicao e um unico `PaymentEvent`. Teste na Task 1.
- Eventos fora de ordem (`payment_failed` depois de `succeeded`): transicao invalida e descartada com warn, evento marcado como processado, sem loop de retry. Teste na Task 1.
- Assinatura invalida, header ausente, segredo errado ou timestamp velho: 400 generico, nada publicado, nada logado do corpo. Teste na Task 2/3.
- `STRIPE_WEBHOOK_SECRET` vazio: o endpoint **nao e registrado** (404), nunca aceita evento sem assinatura. Teste na Task 3.

## Mapa de arquivos

| Arquivo | Responsabilidade |
|---|---|
| `application/port/ProviderEvent.java` (novo) | record neutro: `eventId`, `type`, `providerPaymentId`, `newStatus` |
| `application/port/WebhookEventVerifier.java` (novo) | porta: valida assinatura e traduz payload para `ProviderEvent` |
| `application/port/WebhookEventPublisher.java` (novo) | porta: publica `ProviderEvent` para processamento assincrono |
| `domain/entities/ProcessedWebhookEvent.java` (novo) | entidade da tabela V3 |
| `domain/exception/InvalidWebhookSignatureException.java` (novo) | assinatura invalida -> 400 |
| `infrastructure/persistence/ProcessedWebhookEventRepository.java` (novo) | `existsByStripeEventId` / save |
| `infrastructure/persistence/PaymentRepository.java` (alterar) | `findByProviderPaymentIdForUpdate` |
| `application/service/WebhookEventProcessor.java` (novo) | dedupe + aplica transicao, uma transacao |
| `application/service/PaymentService.java` (alterar) | `applyProviderStatus(Payment, PaymentStatus, Map)` reutilizando `transition` |
| `infrastructure/provider/stripe/StripeWebhookVerifier.java` (novo) | `Webhook.constructEvent` + mapeamento tipo -> status |
| `infrastructure/provider/stripe/StripeProperties.java` (alterar) | campo `webhookSecret` |
| `infrastructure/messaging/KafkaWebhookEventPublisher.java` (novo) | adapter Kafka da porta de publicacao |
| `infrastructure/messaging/WebhookEventListener.java` (novo) | `@KafkaListener` -> `WebhookEventProcessor` |
| `config/KafkaWebhookConfig.java` (novo) | topico, `DefaultErrorHandler` + DLT |
| `api/controller/StripeWebhookController.java` (novo) | endpoint, condicional ao segredo configurado |

---

### Task 1: Processador idempotente de eventos

**Files:**
- Create: `application/port/ProviderEvent.java`, `domain/entities/ProcessedWebhookEvent.java`, `infrastructure/persistence/ProcessedWebhookEventRepository.java`, `application/service/WebhookEventProcessor.java`
- Modify: `infrastructure/persistence/PaymentRepository.java`, `application/service/PaymentService.java`
- Test: `src/test/.../application/service/WebhookEventProcessorTest.java` (Postgres real via `PostgresTestConfiguration`)

**Interfaces:**
- Produces:
  - `record ProviderEvent(String eventId, String type, String providerPaymentId, PaymentStatus newStatus)` (`newStatus == null` = tipo ignorado).
  - `WebhookEventProcessor.process(ProviderEvent event): void` — `@Transactional`; lanca `PaymentNotFoundException` se nao achar o pagamento (para o Kafka retentar).
  - `PaymentRepository.findByProviderPaymentIdForUpdate(String): Optional<Payment>` (`PESSIMISTIC_WRITE`, como `findByIdForUpdate`).
  - `PaymentService.applyProviderStatus(Payment payment, PaymentStatus newStatus, Map<String,Object> extra): Payment` (publico, reaproveita `transition`).

- [ ] **Step 1: Escrever os testes que falham** em `WebhookEventProcessorTest`:
  - `succeededEventMovesPendingToCaptured`: pagamento `PENDING` com `providerPaymentId="pi_test_1"`; `process(new ProviderEvent("evt_1","payment_intent.succeeded","pi_test_1",CAPTURED))`; assert status `CAPTURED`, um `PaymentEvent` `payment.confirmed` com `source=stripe_webhook` e `stripeEventId=evt_1` no payload, uma linha em `processed_webhook_events`.
  - `duplicateEventIsIgnored`: processar o mesmo `evt_1` duas vezes; assert um unico `PaymentEvent` de confirmacao e uma linha processada.
  - `concurrentDuplicateEventAppliesOnce`: 8 threads com o mesmo `evt_1`; assert uma transicao so e nenhuma excecao propagada (a perdedora toma a violacao do UNIQUE e trata como duplicado).
  - `outOfOrderEventIsSkippedAndMarkedProcessed`: pagamento `CAPTURED`, evento `failed` (`FAILED`); assert status continua `CAPTURED`, nenhuma excecao, evento marcado como processado.
  - `unknownPaymentThrowsPaymentNotFound`: `providerPaymentId` inexistente; assert `PaymentNotFoundException` e **nenhuma** linha em `processed_webhook_events` (rollback).
  - `eventWithNullNewStatusIsIgnored`: `newStatus=null`; nada alterado e nada gravado.

- [ ] **Step 2: Rodar e ver falhar**
  Run: `./mvnw test -Dtest=WebhookEventProcessorTest`
  Expected: erro de compilacao (classes nao existem).

- [ ] **Step 3: Implementar** a entidade (UUID gerado, `stripeEventId`, `processedAt`), o repository (`existsByStripeEventId`), `findByProviderPaymentIdForUpdate`, `PaymentService.applyProviderStatus` e `WebhookEventProcessor.process`. Ordem no `process`: ignorar se `newStatus == null`; **primeiro** `saveAndFlush` do registro processado (o UNIQUE decide a corrida; `DataIntegrityViolationException` -> retornar sem fazer nada, como no ADR-003, relendo fora da transacao se necessario); depois buscar o pagamento com lock (`PaymentNotFoundException` desfaz o registro); por fim `applyProviderStatus` capturando `InvalidPaymentStateException` com `log.warn` (so ids e tipos). Payload extra do `PaymentEvent`: `source=stripe_webhook`, `stripeEventId`.

- [ ] **Step 4: Rodar e ver passar**
  Run: `./mvnw test -Dtest=WebhookEventProcessorTest`
  Expected: PASS (6 testes).

- [ ] **Step 5: Guard de segredos e commit**
  Rodar o guard da secao de seguranca, depois:
  ```bash
  git add src && git commit -m "feat: processador idempotente de eventos de webhook"
  ```

---

### Task 2: Verificacao de assinatura (adapter Stripe) e configuracao

**Files:**
- Create: `application/port/WebhookEventVerifier.java`, `domain/exception/InvalidWebhookSignatureException.java`, `infrastructure/provider/stripe/StripeWebhookVerifier.java`
- Modify: `infrastructure/provider/stripe/StripeProperties.java`, `src/main/resources/application.yml`, `.env.example`, `docker-compose.yml`, `knowledgebase/ai/environment.md`, `api/exception/GlobalExceptionHandler.java`
- Test: `src/test/.../infrastructure/provider/stripe/StripeWebhookVerifierTest.java`

**Interfaces:**
- Consumes: `ProviderEvent` (Task 1).
- Produces:
  - `WebhookEventVerifier.verify(String payload, String signatureHeader): ProviderEvent` — lanca `InvalidWebhookSignatureException` (mensagem generica, sem eco do payload).
  - `StripeProperties` ganha o componente `String webhookSecret` (ultimo da lista; ajustar `PaymentProviderConfig` e testes que constroem o record).
  - `GlobalExceptionHandler` mapeia `InvalidWebhookSignatureException` para 400 no formato `ApiErrorResponse` existente.

- [ ] **Step 1: Escrever os testes que falham** em `StripeWebhookVerifierTest`, assinando payloads de teste com `com.stripe.net.Webhook.Util.computeHmacSha256` e o segredo falso `"test-only-signing-secret"` (header `t=<epoch>,v1=<hmac>`):
  - `validSucceededEventMapsToCaptured`: payload `payment_intent.succeeded` -> `ProviderEvent("evt_1","payment_intent.succeeded","pi_test_1",CAPTURED)`.
  - `paymentFailedMapsToFailed` e `canceledMapsToCanceled`.
  - `unsupportedTypeReturnsNullNewStatus` (ex.: `customer.created`): `newStatus == null`, sem excecao.
  - `wrongSecretIsRejected`, `tamperedBodyIsRejected`, `missingHeaderIsRejected` (header `null`), `oldTimestampIsRejected` (timestamp 10 min atras): todos `InvalidWebhookSignatureException`.
  - `exceptionMessageDoesNotLeakPayloadOrSecret`: mensagem nao contem o corpo nem o segredo.

- [ ] **Step 2: Rodar e ver falhar**
  Run: `./mvnw test -Dtest=StripeWebhookVerifierTest`
  Expected: erro de compilacao.

- [ ] **Step 3: Implementar** `StripeWebhookVerifier(StripeProperties)` com `Webhook.constructEvent(payload, header, secret)` (tolerancia padrao do SDK, 300 s); `SignatureVerificationException` e `JsonSyntaxException` viram `InvalidWebhookSignatureException`. Mapeamento tipo -> status: `payment_intent.succeeded`->`CAPTURED`, `payment_intent.payment_failed`->`FAILED`, `payment_intent.canceled`->`CANCELED`; qualquer outro -> `newStatus=null`. `providerPaymentId` vem do `PaymentIntent` do evento. Configuracao: `webhook-secret: ${STRIPE_WEBHOOK_SECRET:}` em `application.yml`; `STRIPE_WEBHOOK_SECRET=` (vazio) no `.env.example`; `STRIPE_WEBHOOK_SECRET: ${STRIPE_WEBHOOK_SECRET:-}` no compose; linha na tabela de variaveis do `environment.md` (descrever o uso, sem valor de exemplo real).

- [ ] **Step 4: Rodar e ver passar**
  Run: `./mvnw test -Dtest=StripeWebhookVerifierTest,StripePaymentProviderTest`
  Expected: PASS.

- [ ] **Step 5: Guard de segredos e commit**
  ```bash
  git add src .env.example docker-compose.yml knowledgebase && git commit -m "feat: verificacao de assinatura do webhook Stripe"
  ```

---

### Task 3: Endpoint, publicacao no Kafka e consumer

**Files:**
- Create: `application/port/WebhookEventPublisher.java`, `api/controller/StripeWebhookController.java`, `infrastructure/messaging/KafkaWebhookEventPublisher.java`, `infrastructure/messaging/WebhookEventListener.java`, `config/KafkaWebhookConfig.java`
- Test: `src/test/.../api/controller/StripeWebhookControllerTest.java` (MockMvc, publisher mockado), `src/test/.../infrastructure/messaging/WebhookFlowIntegrationTest.java` (Postgres + Kafka reais)

**Interfaces:**
- Consumes: `WebhookEventVerifier` (Task 2), `WebhookEventProcessor` (Task 1).
- Produces:
  - `WebhookEventPublisher.publish(ProviderEvent event): void`.
  - `POST /api/v1/webhooks/stripe`: `consumes` JSON, `@RequestBody String payload`, `@RequestHeader(value="Stripe-Signature", required=false) String signature`. 200 (corpo vazio) quando valido, publicado ou ignorado; 400 assinatura invalida; nao registrado quando o segredo esta vazio (`@ConditionalOnExpression("!'${payment.stripe.webhook-secret:}'.isBlank()")`, no controller e no verifier).
  - Topico `payment.webhook-events` (chave = `providerPaymentId`, valor = `ProviderEvent` em JSON string), grupo `payment-gateway-webhook`; `DefaultErrorHandler` com `FixedBackOff(1000L, 5)` e `DeadLetterPublishingRecoverer` para `payment.webhook-events.DLT`.

- [ ] **Step 1: Escrever os testes que falham.**
  `StripeWebhookControllerTest` (segredo falso via `@TestPropertySource`, publisher `@MockitoBean`):
  - `validSignedEventReturns200AndPublishes`.
  - `ignoredTypeReturns200AndDoesNotPublish`.
  - `invalidSignatureReturns400AndDoesNotPublish`; `missingSignatureHeaderReturns400`.
  - `endpointNotRegisteredWithoutSecret`: contexto sem `webhook-secret` -> `POST` retorna 404.
  `WebhookFlowIntegrationTest` (Kafka e Postgres reais, provider `fake`, segredo falso):
  - `signedSucceededEventCapturesPayment`: pagamento `PENDING` com `providerPaymentId="pi_test_1"`; `POST` assinado; com Awaitility (ate 10 s) status vira `CAPTURED`.
  - `eventBeforePaymentExistsGoesToDltAndConsumerKeepsWorking`: `POST` para `pi_inexistente`; evento aparece na `.DLT`; um segundo evento valido depois ainda e processado.

- [ ] **Step 2: Rodar e ver falhar**
  Run: `./mvnw test -Dtest='StripeWebhookControllerTest,WebhookFlowIntegrationTest'`
  Expected: erro de compilacao.

- [ ] **Step 3: Implementar** o controller fino (verifica -> se `newStatus != null` publica -> 200; `@Operation`/`@ApiResponses` 200/400, sem exemplos com valores de assinatura), o adapter Kafka (`KafkaTemplate<String,String>`, serializar o record com o mapper JSON ja disponivel no projeto; confirmar o pacote Jackson do Boot 4.1 antes de importar), o listener (`@KafkaListener(topics="payment.webhook-events", groupId="payment-gateway-webhook")` -> `WebhookEventProcessor.process`) e a configuracao do topico/erro. O controller nao loga corpo nem header.

- [ ] **Step 4: Rodar e ver passar**
  Run: `./mvnw test -Dtest='StripeWebhookControllerTest,WebhookFlowIntegrationTest'`
  Expected: PASS.

- [ ] **Step 5: Suite completa, guard de segredos e commit**
  Run: `./mvnw test` -> Expected: todos os testes (os 27 anteriores + novos) passam.
  ```bash
  git add src && git commit -m "feat: endpoint de webhook Stripe publicando no Kafka e consumer"
  ```

---

### Task 4: Documentacao e validacao manual

**Files:**
- Create: `knowledgebase/features/010-webhook-stripe.md` (a partir de `_template.md`)
- Modify: `knowledgebase/features/index.md`, `knowledgebase/ai/decisions.md` (ADR-010), `knowledgebase/ai/architecture.md`, `knowledgebase/ai/operations.md`, `README.md` (status 4.7 e fluxo), `NOTAS.md` (passo de teste com `stripe listen`)

**Interfaces:** nenhuma.

- [ ] **Step 1: Escrever ADR-010** (decisoes: eventos tratados e ignorados; `charge.refunded` fica **fora** (estornos feitos fora da API exigiriam sincronizar `refunded_amount`, e estornos pela API ja gravam o evento); dedupe no consumer, nao no controller; DLT apos 5 tentativas; endpoint inexistente sem segredo).
- [ ] **Step 2: Escrever a feature doc** com o que mudou, por que, arquivos/variaveis afetados, como validar e pendencias (reconciliacao de `PENDING` antigos, `charge.refunded`, outbox v3, teste de carga k6 do webhook).
- [ ] **Step 3: Validacao manual** (so se o dono tiver o Stripe CLI logado e `.env` local preenchido; o agente nao le nem imprime o `.env`):
  ```bash
  stripe listen --forward-to localhost:8080/api/v1/webhooks/stripe
  stripe trigger payment_intent.succeeded
  ```
  Conferir nos logs da app transicao `PENDING -> CAPTURED` e `GET /api/v1/payments/{id}/history` com o evento `source=stripe_webhook`. O `payment_intent` do `trigger` nao existe no banco: esperado ir para a DLT (valida o Review Focus 1). Para ver a transicao real, criar um pagamento via API com `STRIPE_TEST_PAYMENT_METHOD` vazio e confirma-lo pelo Stripe CLI.
- [ ] **Step 4: Checks finais antes do push**
  ```bash
  git ls-files | xargs grep -lE "whsec_[A-Za-z0-9]{8,}|sk_(test|live)_[A-Za-z0-9]{8,}" ; git check-ignore .env
  ```
  Expected: primeiro comando sem saida; segundo imprime `.env`.
- [ ] **Step 5: Commit e push**
  ```bash
  git add knowledgebase README.md NOTAS.md && git commit -m "docs: webhook Stripe (ADR-010, feature 010 e status 4.7)" && git push -u origin feat/webhook-stripe
  ```
  O dono abre o PR para `hml`.

---

## Self-review

- **Cobertura da spec:** assinatura HMAC (Task 2), responder rapido e publicar no Kafka (Task 3), idempotencia de consumo com `ProcessedWebhookEvent` (Task 1), segredo por config (Task 2), docs/AGENTS (Task 4). Fora de escopo, registrado no ADR: `charge.refunded`, outbox (v3), reconciliacao, k6 do webhook.
- **Consistencia de tipos:** `ProviderEvent`, `WebhookEventVerifier.verify`, `WebhookEventProcessor.process`, `WebhookEventPublisher.publish` e `applyProviderStatus` usam as mesmas assinaturas em todas as tasks.
- **Proporcao:** corpos de metodo omitidos; so assinaturas, nomes de teste e valores fixados.
