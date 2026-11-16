# Webhook Stripe -> Kafka -> consumer idempotente

## Status

- Tipo: feature
- Status: implementado e validado manualmente com Stripe CLI (2026-11-16)
- Ultima atualizacao: 2026-11-16

## Objetivo

Receber eventos do Stripe (sandbox) por webhook assinado, enfileirar no Kafka e aplicar a mudanca de status do pagamento de forma idempotente. Faz um `PENDING` convergir para `CAPTURED`/`FAILED`/`CANCELED` sem depender da resposta sincronica (pendencia do ADR-006).

## Contexto

README secao 4.7 e 6; ADR-002 (so `infrastructure/provider/stripe` importa `com.stripe.*`), ADR-005/006 e ADR-010 (decisoes desta feature). Plano: `docs/superpowers/plans/2026-11-16-webhook-stripe.md`.

## Comportamento

- `POST /api/v1/webhooks/stripe`: `200` para evento valido (publicado) ou de tipo ignorado; `400` para assinatura ausente, invalida, adulterada, com segredo errado ou timestamp com mais de 300 s. Sem `STRIPE_WEBHOOK_SECRET` o endpoint nao existe (`404`).
- Publica `ProviderEvent` (JSON) em `payment.webhook-events`, chave = `providerPaymentId`, e espera o ack do broker; falha no publish vira `500` e o Stripe reenvia.
- Consumer (`payment-gateway-webhook`): registra `stripe_event_id`, trava o pagamento (`PESSIMISTIC_WRITE`), aplica a transicao e grava `PaymentEvent` com `source=stripe_webhook` e `stripeEventId`, na mesma transacao.
- Duplicado (inclusive concorrente) = uma transicao so. Fora de ordem = descartado com `warn`. Pagamento inexistente = 5 retentativas e `payment.webhook-events.DLT`.
- Tipos tratados: `payment_intent.succeeded`, `payment_intent.payment_failed`, `payment_intent.canceled`.

## Arquivos E Pontos De Entrada

- `api/controller/StripeWebhookController.java`, `api/exception/GlobalExceptionHandler.java` (400 de assinatura, 404 de rota inexistente)
- `application/port/` (`ProviderEvent`, `WebhookEventVerifier`, `WebhookEventPublisher`), `application/service/WebhookEventProcessor.java`, `PaymentService.applyProviderStatus`
- `infrastructure/provider/stripe/StripeWebhookVerifier.java`, `StripeProperties.webhookSecret`
- `infrastructure/messaging/` (`KafkaWebhookEventPublisher`, `WebhookEventListener`), `config/KafkaWebhookConfig.java`
- `domain/entities/ProcessedWebhookEvent.java`, `infrastructure/persistence/ProcessedWebhookEventRepository.java`, `PaymentRepository.findByProviderPaymentIdForUpdate`
- `pom.xml` (`spring-boot-starter-kafka`), `src/test/resources/application.properties` (Kafka desligado nos testes so-Postgres)
- Testes: `WebhookEventProcessorTest`, `StripeWebhookVerifierTest`, `StripeWebhookControllerTest`, `StripeWebhookControllerDisabledTest`, `WebhookFlowIntegrationTest`

## Variaveis De Ambiente

- `STRIPE_WEBHOOK_SECRET` (novo; vazio por padrao; em `.env.example`, `docker-compose.yml` e `knowledgebase/ai/environment.md`). Fica so no `.env` local.
- `application.yml`: `spring.kafka.consumer.auto-offset-reset=earliest`.

## Validacao

```bash
./mvnw test    # 61 testes; integracao exige Docker (Postgres e Kafka via Testcontainers)
```

Manual (dono, com Stripe CLI logado e `.env` local preenchido; o agente nao le o `.env`):

```bash
docker compose up -d --build app
stripe listen --events payment_intent.succeeded,payment_intent.payment_failed,payment_intent.canceled \
  --forward-to localhost:8080/api/v1/webhooks/stripe   # o segredo impresso vai direto para o .env
```

O `stripe listen` exige `--events` (ou `--all-snapshot`). O `stripe trigger payment_intent.succeeded` falha na API atual da conta (`payment_method_types` nao e mais suportado), entao nao serve para o teste.

Fluxo validado: crie um pagamento pela API com `STRIPE_TEST_PAYMENT_METHOD` vazio (fica `PENDING`), confirme-o com `stripe payment_intents confirm <pi_...> --payment-method pm_card_visa` e confira `GET /api/v1/payments/{id}` (`CAPTURED`) e `/history` (evento com `source=stripe_webhook`). Idempotencia: `stripe events resend <evt_...>` responde `200` e nao gera evento novo.

## Pendencias

- `charge.refunded` (estorno feito fora da API): plano em `docs/superpowers/plans/2026-11-16-pendencia-webhook-charge-refunded.md`.
- Reconciliacao de `PENDING` antigos; outbox/poller de `payment_events` (v3); k6 do webhook (cenario 3).
- Dono: ativar Secret scanning e Push protection no GitHub.
- Topico atual `payment.webhook-events` difere da tabela de topicos do README secao 5 (`payment.confirmed` etc., ligados ao outbox da v3).
