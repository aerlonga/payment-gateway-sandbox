# Pendencia: webhook `charge.refunded` (estorno feito fora da API)

**Status:** nao iniciado. Fica para a task seguinte a `feat/webhook-stripe`.
**Branch sugerida:** `feat/webhook-charge-refunded`, criada de `hml` **depois** do merge do webhook base.
**Depende de:** plano `2026-11-16-webhook-stripe.md` (Tasks 1 a 3: `ProviderEvent`, `WebhookEventProcessor`, verifier e consumer Kafka).

## Contexto

O plano do webhook base trata so `payment_intent.succeeded`, `payment_intent.payment_failed` e `payment_intent.canceled`. O evento `charge.refunded` foi deixado de fora de proposito: estorno feito **fora** da nossa API (ex.: pelo dashboard do Stripe) nao atualiza `payments.refunded_amount` nem a status do pagamento. Enquanto isso nao existir, o banco local pode divergir do Stripe nesse caso.

## Por que nao entrou no PR do webhook base

- O nucleo (assinatura, Kafka, idempotencia) ja e uma entrega completa e testavel.
- Estorno mexe em dinheiro e em concorrencia; misturar no mesmo PR aumenta o risco de bug financeiro.
- O cenario so ocorre com estorno manual no dashboard, raro no projeto de estudo.

## Problemas a resolver

1. **Estorno duplicado.** Estorno feito pela nossa API (`PaymentService.refund`) ja grava `refunded_amount` e um `PaymentEvent` com `providerRefundId`. Depois o Stripe manda `charge.refunded` desse mesmo estorno. Sem distinguir, o valor seria contado duas vezes. Idea: identificar pelo id do refund do Stripe e ignorar quando ja existir evento com esse `providerRefundId` para o pagamento.
2. **O evento traz total acumulado, nao delta.** `charge.refunded` informa o total estornado da cobranca. Aplicar `novoTotal - refunded_amount` (nunca somar o total de novo). Eventos repetidos ou fora de ordem nao podem reduzir nem passar de `amount` (`CHECK (0 <= refunded_amount <= amount)` da V4).
3. **Concorrencia com o `refund` da API.** `PaymentService.refund` segura lock pessimista (`findByIdForUpdate`) durante a chamada ao provider (ADR-004). O processador do webhook deve usar o mesmo lock (`findByProviderPaymentIdForUpdate`, criado na Task 1 do plano base) e a regra de quem ganha deve ser testada.
4. **Estado final.** Ao chegar em `amount`, o pagamento vira `REFUNDED`; antes disso segue `CAPTURED` (ADR-004). Estorno em pagamento que nao esta `CAPTURED` deve ser descartado com `warn`, sem retry, e o evento marcado como processado.

## Escopo da task

- Mapear `charge.refunded` no `StripeWebhookVerifier`. `ProviderEvent` precisa carregar o total estornado e o id do refund; ampliar o record de forma compativel (campos novos opcionais) e manter `com.stripe.*` so em `infrastructure/provider/stripe` (ADR-002).
- Novo caminho no `WebhookEventProcessor` (ou metodo dedicado em `PaymentService`) que aplica o delta, grava `PaymentEvent` `payment.refunded` com `source=stripe_webhook`, `stripeEventId`, `refundedAmount`, `totalRefunded` e `remaining`, na mesma transacao.
- ADR novo em `knowledgebase/ai/decisions.md` (proximo numero livre) descrevendo a regra de deduplicacao contra estorno pela API.
- Feature doc em `knowledgebase/features/` e linha na tabela de `index.md`; atualizar status no `README.md`.

## Testes obrigatorios (Postgres real, Testcontainers)

- `refundFromApiThenWebhookDoesNotDoubleCount`: estorno pela API seguido do `charge.refunded` correspondente deixa `refunded_amount` igual ao valor real.
- `externalPartialRefundUpdatesBalance`: estorno parcial so no Stripe aumenta `refunded_amount` pelo delta e mantem `CAPTURED`.
- `externalFullRefundMovesToRefunded`: estorno total externo leva a `REFUNDED`.
- `duplicateChargeRefundedEventAppliesOnce` e `outOfOrderCumulativeTotalNeverDecreases`.
- `chargeRefundedOnNonCapturedPaymentIsSkipped`: sem excecao, sem retry, marcado como processado.
- `webhookAndApiRefundConcurrentlyKeepInvariant`: `0 <= refunded_amount <= amount` sempre.

## Seguranca de segredos

Mesmas regras do plano base: nenhum segredo em codigo, teste, doc, log ou commit; segredo de assinatura so via `STRIPE_WEBHOOK_SECRET` no `.env` local; guard de grep por `whsec_`/`sk_*`/`pk_*` antes de cada commit e do push; testes usam segredo falso definido no proprio teste. Nao logar corpo nem header do webhook.

## Regras do projeto que continuam valendo

Commits em portugues, sem acento, prefixo convencional, **sem `Co-Authored-By`** (ADR-008). O agente faz commit e push; o dono abre o PR e faz o merge. Dinheiro em centavos (`Long`). Migration nova so como `V<n>__*.sql`, sem editar as aplicadas.

## Como validar

`./mvnw test` verde; com Stripe CLI logado e `.env` local preenchido, criar um pagamento confirmado, estornar parte pelo dashboard do sandbox e conferir `GET /api/v1/payments/{id}` (`refundedAmount`) e `/history` (evento com `source=stripe_webhook`).

## Riscos em aberto

- Se o estorno pela API falhar depois de o Stripe ja ter criado o refund (timeout, ADR-006), o webhook passa a ser a unica fonte de verdade; a regra de deduplicacao precisa cobrir esse caso.
- `refund` da API ainda nao usa Idempotency-Key propria no Stripe (ADR-004); considerar junto.
