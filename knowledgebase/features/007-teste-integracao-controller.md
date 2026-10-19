# Teste de integracao do controller

## Status

- Tipo: chore
- Status: implementado
- Ultima atualizacao: 2026-10-19

## Objetivo

Cobrir a API HTTP de ponta a ponta com o contexto Spring completo, fechando a v1.

## Contexto

Havia teste de contrato (`PaymentControllerContractTest`, MockMvc standalone com service mockado), mas nada exercitando controller + validacao + service + JPA/Flyway + handler de erros juntos.

## Comportamento

`PaymentControllerIntegrationTest` (`@SpringBootTest` + `PostgresTestConfiguration`, provider `fake`, MockMvc via `webAppContextSetup`) cobre:

- POST: 201 com `Location` e `clientSecret`; replay idempotente 200; mesma chave com payload diferente 409; payload invalido, header ausente e JSON malformado 400.
- GET por id (sem `clientSecret`), 404 em get/history/cancel/refund de id inexistente.
- Estorno parcial seguido de total (saldo, status `CAPTURED` -> `REFUNDED`, historico com 4 eventos), estorno acima do saldo 400, estorno de pagamento ja estornado 409.
- Cancelamento de pagamento `CAPTURED` 409.
- Listagem por cliente: paginacao, filtro por status e cliente sem pagamentos.

## Arquivos E Pontos De Entrada

- `src/test/java/com/aerlon/payment_gateway_sandbox/api/controller/PaymentControllerIntegrationTest.java`

## Variaveis De Ambiente

Nenhuma. Requer Docker (Testcontainers).

## Validacao

```bash
./mvnw test -Dtest=PaymentControllerIntegrationTest
```

## Pendencias

- `PaymentGatewaySandboxApplicationTests.contextLoads` falha (ja falhava antes): `ConnectionDetailsNotFoundException` para o `@ServiceConnection` do `kafkaContainer` em `TestcontainersConfiguration`.
- Cancelamento com sucesso (`PENDING` -> `CANCELED`) nao e coberto aqui: o provider fake ja nasce `CAPTURED`.
