# `clientSecret` na resposta e `paymentMethod`/`description` no request

## Status

- Tipo: feature
- Status: implementado
- Ultima atualizacao: 2026-10-19

## Objetivo

Fechar o contrato do README 4.1: devolver o `clientSecret` para o front confirmar o pagamento e aceitar `paymentMethod` e `description` no `POST /payments`.

## Contexto

ADR-009. Sem o `clientSecret` o front nao confirma um `PaymentIntent` que ficou `PENDING` (sem `STRIPE_TEST_PAYMENT_METHOD`). `paymentMethod` e o **tipo** do meio (`card`), nao um `pm_...`.

## Comportamento

- `POST` 201 devolve `clientSecret`; nao e gravado nem logado e nunca vem em `GET`.
- Replay (200) de pagamento `PENDING` com `providerPaymentId` busca o segredo no provider (`getPayment`); falha do provider -> replay sem o campo.
- `paymentMethod` (opcional, `^[a-z][a-z_]{1,29}$`) e `description` (opcional, ate 255) sao validados, gravados (V5), devolvidos e comparados na idempotencia (valor diferente com a mesma chave = 409).
- Stripe: `description` vai no `PaymentIntent`; com `paymentMethod` usa `payment_method_types`, sem ele `automatic_payment_methods`.

## Arquivos E Pontos De Entrada

- `api/dto/{CreatePaymentRequest,PaymentResponse}.java`, `application/service/PaymentService.java`
- `application/port/CreatePaymentCommand.java`, `infrastructure/provider/stripe/StripePaymentProvider.java`
- `domain/entities/Payment.java`, `db/migration/V5__add_payment_method_and_description_to_payments.sql`
- `src/test/.../application/service/PaymentServiceCreateTest.java`, `api/controller/PaymentControllerContractTest.java`, `StripePaymentProviderTest`

## Variaveis De Ambiente

Nenhuma nova.

## Validacao

`./mvnw test -Dtest='PaymentServiceCreateTest,PaymentControllerContractTest,StripePaymentProviderTest'`. No Stripe real, com `STRIPE_TEST_PAYMENT_METHOD` vazio: `POST` com `paymentMethod: card` -> 201 `PENDING` com `clientSecret`; replay -> 200 com `clientSecret`; mesma chave com outra `description` -> 409; `GET` sem o campo; `paymentMethod: "CARD!"` -> 400.

## Pendencias

- Teste de integracao do controller com o contexto completo (o projeto nao tem o modulo `spring-boot-webmvc-test`; o teste atual e MockMvc standalone com service mockado).
- Meios alem de `card` (ex.: `pix`) dependem de habilitacao na conta Stripe; nao exercitados.
