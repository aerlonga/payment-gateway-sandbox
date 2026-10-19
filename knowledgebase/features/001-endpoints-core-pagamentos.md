# Endpoints core de pagamento, eventos e Swagger

## Status

- Tipo: feature
- Status: implementado (faltam `clientSecret`, `paymentMethod`/`description` e testes de controller)
- Ultima atualizacao: 2026-10-19

## Objetivo

API de cobranca com criacao idempotente, consulta, listagem por cliente, cancelamento, historico de eventos e documentacao Swagger (README 4.1 a 4.6).

## Contexto

Primeira parte (v1) do roadmap. `customerId` e so uma referencia (sem tabela de cliente): dados pessoais ficam em outro servico (menos dado sensivel, responsabilidade unica). Todo pagamento nasce `PENDING`.

## Comportamento

- `POST /api/v1/payments` exige `Idempotency-Key`; 201 ao criar, 200 no replay do mesmo payload, 409 com payload diferente.
- `GET /{id}` (404 se nao existe); `GET /?customerId=&status=` paginado — cliente sem pagamentos devolve 200 com `content: []`, nao 404.
- `POST /{id}/cancel` (a partir de `PENDING`/`AUTHORIZED`), `GET /{id}/history`.
- Maquina de estados em `PaymentService.ALLOWED_TRANSITIONS` (ver [[../ai/architecture|Arquitetura]]).
- Toda criacao/mudanca grava `payment_events` (`PENDING_PUBLISH`) na mesma transacao (ADR-005).
- Swagger com `@ParameterObject` na listagem (`page`, `size`, `sort` separados).

## Arquivos E Pontos De Entrada

- `api/controller/PaymentController.java`, `api/dto/*`, `api/exception/GlobalExceptionHandler.java`
- `application/service/PaymentService.java`, `application/mapper/PaymentMapper.java`
- `domain/entities/{Payment,PaymentEvent}.java`, `domain/enums/*`, `domain/exception/*`
- `db/migration/V1`, `V2`, `V3` (V3: tabela de webhook sem codigo ainda)
- `config/OpenApiConfig.java`, `Dockerfile`, `docker-compose.yml`

## Variaveis De Ambiente

`DB_*`, `KAFKA_BOOTSTRAP_SERVERS` (ver [[../ai/environment|Ambiente]]).

## Validacao

Swagger em `http://localhost:8080/swagger-ui.html` seguindo o fluxo do `NOTAS.md`: criar, repetir com a mesma chave (200), `history`, `cancel`.

## Pendencias

- `clientSecret` na resposta do `POST`; `paymentMethod` e `description` no request (README 4.1).
- Testes de controller.
- Webhook (4.7) e Kafka: v2/v3.
