# Teste de carga k6 (v4)

## Status

- Tipo: feature
- Status: implementado (webhook em rajada pendente)
- Ultima atualizacao: 2026-10-19

## Objetivo

Medir onde a API satura e provar a idempotencia sob concorrencia com o `FakePaymentProvider`, sem tocar no Stripe (rate limit do sandbox).

## Contexto

README secao 7. ADR-006 deixou a decisao sobre criacao assincrona para depois dos numeros do k6.

## Comportamento

- `create-payment.js`: chegada constante (default 25 req/s por 1 min), `Idempotency-Key` unica; thresholds de erro, p95 e p99.
- `idempotency-race.js`: 10 requisicoes paralelas (`http.batch`) com a mesma chave; exige exatamente um 201, demais 200 com o mesmo id e zero 5xx.
- `read-heavy.js`: `setup()` semeia 3000 pagamentos para 100 clientes; mistura de listagem por cliente, por cliente + status e busca por id.
- Compose repassa `SPRING_PROFILES_ACTIVE` e `FAKE_*` para a app.
- Achado: teto de ~32 criacoes/s (pool de 10 conexoes / ~0,3 s do provider, que roda dentro da transacao). Resultados na secao 7.3.1 do README.

## Arquivos E Pontos De Entrada

- `load-tests/k6/create-payment.js`, `idempotency-race.js`, `read-heavy.js`, `lib/config.js`
- `docker-compose.yml`, `.env.example`
- `README.md` (secoes 7.2, 7.3.1, 13), `knowledgebase/ai/{operations,environment,architecture}.md`

## Variaveis De Ambiente

`SPRING_PROFILES_ACTIVE`, `FAKE_LATENCY_MS_MIN`, `FAKE_LATENCY_MS_MAX`, `FAKE_FAILURE_RATE` (sem valor no `.env.example`).

## Validacao

```bash
SPRING_PROFILES_ACTIVE=loadtest PAYMENT_PROVIDER=fake docker compose up -d --build app
docker run --rm --network host -v $PWD/load-tests/k6:/k6:ro grafana/k6 run /k6/create-payment.js
docker run --rm --network host -v $PWD/load-tests/k6:/k6:ro grafana/k6 run /k6/idempotency-race.js
docker run --rm --network host -v $PWD/load-tests/k6:/k6:ro grafana/k6 run /k6/read-heavy.js
```

## Pendencias

- Cenario 3 (webhook em rajada): depende de webhook e Kafka (v2).
- Comparacao com/sem indice `(customer_id, status)` exige seed maior e `EXPLAIN`.
- Decidir se vale reduzir a transacao de criacao (ADR-006) dado o teto de ~32 req/s.
- Rodada unica, na mesma maquina da app; sem repeticao para variancia.
