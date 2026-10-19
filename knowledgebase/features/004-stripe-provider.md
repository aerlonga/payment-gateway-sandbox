# `StripePaymentProvider` com circuit breaker

## Status

- Tipo: feature
- Status: implementado (validado contra o sandbox real)
- Ultima atualizacao: 2026-10-19

## Objetivo

Cobrar e estornar de verdade no Stripe (sandbox) pela porta `PaymentProvider`.

## Contexto

README 2.1 e 6. Revisao automatica de seguranca apontou dois pontos, corrigidos: recusa de cartao nao pode contar no circuit breaker e a chave/metadados de idempotencia precisam ser deterministicos (ADR-003, ADR-007).

## Comportamento

- `createPayment`: `PaymentIntent.create` (centavos, moeda minuscula, metadados `idempotencyKey` e `customerId`), com a chave `customerId:Idempotency-Key`.
- Com `STRIPE_TEST_PAYMENT_METHOD` (ex.: `pm_card_visa`): `confirm=true`, sem redirects, resultado `CAPTURED`. Sem ele: `PENDING` ate a confirmacao.
- `getPayment`, `cancel` (cancela o PI), `refund` (`Refund.create` com `reason` mapeado quando possivel).
- Mapeamento de status do PI: `succeeded`->`CAPTURED`, `requires_capture`->`AUTHORIZED`, `canceled`->`CANCELED`, demais->`PENDING`.
- `CardException` -> `FAILED`, fora do breaker; rede/timeout/circuito aberto -> `PaymentProviderException` (502).
- Circuit breaker `stripe` (Resilience4j, config default + `ignoreExceptions(CardException)`); SDK com `max-network-retries=2`.

## Arquivos E Pontos De Entrada

- `infrastructure/provider/stripe/{StripePaymentProvider,StripeProperties}.java`
- `config/PaymentProviderConfig.java` (`stripeProvider`, `stripeCircuitBreaker`)
- `application.yml` (`payment.stripe.*`), `.env.example`, `docker-compose.yml`
- `src/test/.../provider/stripe/StripePaymentProviderTest.java`

## Variaveis De Ambiente

`PAYMENT_PROVIDER=stripe`, `STRIPE_API_KEY` (secreta, obrigatoria), `STRIPE_TEST_PAYMENT_METHOD`.

## Validacao

`./mvnw test -Dtest=StripePaymentProviderTest`. Manual: `PAYMENT_PROVIDER=stripe` + chave `sk_test_` + `pm_card_visa`; `POST /payments` retorna `CAPTURED` com `pi_...` e o pagamento aparece no painel do Stripe (modo teste). Em rede com inspecao TLS, ver [[../ai/environment|Ambiente]].

## Pendencias

- Timeout do Stripe vira 502 com rollback, mas o Stripe pode ter processado: reconciliar com o webhook (ADR-006).
- Circuit breaker com configuracao default; sem retry/time limiter proprios de Resilience4j.
- Recusa de cartao (`CardException`) coberta por teste unitario, ainda nao exercitada no sandbox.
