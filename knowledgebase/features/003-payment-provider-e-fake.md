# Interface `PaymentProvider` e `FakePaymentProvider`

## Status

- Tipo: feature
- Status: implementado
- Ultima atualizacao: 2026-10-19

## Objetivo

Isolar o gateway externo atras de uma porta e ter um provider simulado para teste de carga e testes automatizados (o sandbox do Stripe tem rate limit baixo).

## Contexto

ADR-002. O README (secoes 2.1 e 7) especifica a interface, o fake com latencia/falha configuraveis e o profile `loadtest`.

## Comportamento

- `PaymentService` chama a porta em `create`, `cancel` e `refund`; nunca o Stripe.
- `payment.provider` (`fake` default | `stripe`) escolhe o bean; o campo `provider` saiu do request.
- Fake: latencia aleatoria `[min,max]` ms, `failure-rate` (recusa -> `FAILED`), aprovacao ja `CAPTURED`, ids `pi_fake_*`/`re_fake_*`.
- `PaymentProviderException` -> 502.
- Transicao `PENDING -> CAPTURED` liberada na maquina de estados.

## Arquivos E Pontos De Entrada

- `application/port/*`
- `infrastructure/provider/fake/{FakePaymentProvider,FakeProviderProperties}.java`
- `config/PaymentProviderConfig.java`, `domain/exception/PaymentProviderException.java`
- `src/main/resources/application.yml`, `application-loadtest.yml`
- `src/test/.../provider/fake/FakePaymentProviderTest.java`

## Variaveis De Ambiente

`PAYMENT_PROVIDER`, `FAKE_LATENCY_MS_MIN`, `FAKE_LATENCY_MS_MAX`, `FAKE_FAILURE_RATE`; `SPRING_PROFILES_ACTIVE=loadtest`.

## Validacao

`./mvnw test -Dtest=FakePaymentProviderTest`; criar pagamento com o fake e conferir `providerPaymentId` `pi_fake_...` e status `CAPTURED`.

## Pendencias

- Calibrar a latencia do fake com medicoes reais do Stripe e documentar os valores (README 7.2).
- O fake nao reproduz a idempotencia do Stripe (ADR-002).
