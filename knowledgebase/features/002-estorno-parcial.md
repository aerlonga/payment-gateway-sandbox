# Estorno total e parcial com controle de saldo

## Status

- Tipo: feature
- Status: implementado
- Ultima atualizacao: 2026-10-19

## Objetivo

`POST /{id}/refund` estornar parte ou todo o valor, varias vezes, sem ultrapassar o que foi capturado (README 4.4).

## Contexto

O primeiro modelo levava qualquer estorno a `REFUNDED` (final). Estorno parcial de 5000 num pagamento de 19990 bloqueava os seguintes e dizia "tudo devolvido". Decisao e alternativas em ADR-004.

## Comportamento

- So em `CAPTURED`; qualquer outro status -> 409 ("status atual: ...").
- `amount` omitido = saldo restante; `amount` acima do saldo -> 400.
- `refunded_amount` acumula; enquanto `< amount` o pagamento segue `CAPTURED`; ao igualar vira `REFUNDED`.
- Cada estorno chama `PaymentProvider.refund` e grava `payment.refunded` (`refundedAmount`, `totalRefunded`, `remaining`, `providerRefundId`).
- Lock pessimista no pagamento serializa estornos concorrentes.
- `PaymentResponse` ganhou `refundedAmount`; `reason` aceita `requested_by_customer`, `duplicate`, `fraudulent` (mapeados no Stripe) ou texto livre (vai em metadata).

## Arquivos E Pontos De Entrada

- `application/service/PaymentService.refund`
- `infrastructure/persistence/PaymentRepository.findByIdForUpdate`
- `domain/entities/Payment.refundedAmount`, `api/dto/{PaymentResponse,RefundRequest}`
- `db/migration/V4__add_refunded_amount_to_payments.sql`
- `src/test/.../application/service/PaymentServiceRefundTest.java`

## Variaveis De Ambiente

Nenhuma.

## Validacao

Fake e Stripe real: pagamento de 19990 -> parcial 5000 (`CAPTURED`, 5000) -> 15000 acima do saldo (400) -> parcial 4990 -> resto sem `amount` (`REFUNDED`, 19990) -> novo estorno (409). `./mvnw test -Dtest=PaymentServiceRefundTest`.

## Pendencias

- Idempotency-Key propria no `Refund.create` do Stripe.
- Lock fica preso durante a chamada ao provider (medir no k6).
