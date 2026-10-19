# Idempotencia sob concorrencia

## Status

- Tipo: fix
- Status: implementado
- Ultima atualizacao: 2026-10-19

## Objetivo

Requisicoes simultaneas com a mesma `Idempotency-Key` criam um unico pagamento; as demais recebem o mesmo resultado (200) ou conflito (409), nunca 500.

## Contexto

O fluxo "consulta e depois insere" deixava a perdedora da corrida estourar `uq_payments_idempotency_key` no commit (500), depois de ja ter chamado o provider. Reproduzido por teste com 16 threads antes da correcao. ADR-003.

## Comportamento

- `PaymentService.create` roda numa `TransactionTemplate`; o pagamento e enviado ao banco com `saveAndFlush` antes de chamar o provider.
- `DataIntegrityViolationException` da perdedora -> releitura por `findByIdempotencyKey` fora da transacao abortada -> replay (200) se o payload bate, `IdempotencyConflictException` (409) se nao.
- A perdedora espera o commit da vencedora, segurando uma conexao do pool.

## Arquivos E Pontos De Entrada

- `application/service/PaymentService.java` (`create`, `doCreate`, `replay`)
- `src/test/.../PostgresTestConfiguration.java`
- `src/test/.../application/service/PaymentIdempotencyRaceTest.java`

## Variaveis De Ambiente

Nenhuma.

## Validacao

`./mvnw test -Dtest=PaymentIdempotencyRaceTest` (precisa de Docker): mesma chave e payload em 16 threads -> 1 `created`, 15 replays, 1 linha e 1 evento `payment.created`; mesma chave e payloads diferentes -> 1 sucesso e 15 conflitos.

## Pendencias

- Medir no k6 (cenario 2) o efeito no pool de conexoes quando o provider e lento; alternativa em ADR-006.
