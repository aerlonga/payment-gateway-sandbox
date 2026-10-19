# Features

Contexto de cada entrega (o que mudou, por que, como validar, pendencias). Use [[_template|o template]] para criar ou atualizar.

| # | Entrega | Tipo | Status |
|---|---|---|---|
| [000](000-knowledge-base-ia.md) | Knowledge base de IA e AGENTS.md | chore | implementado |
| [001](001-endpoints-core-pagamentos.md) | Endpoints core de pagamento, eventos e Swagger | feature | implementado |
| [002](002-estorno-parcial.md) | Estorno total e parcial com controle de saldo | feature | implementado |
| [003](003-payment-provider-e-fake.md) | Interface `PaymentProvider` e `FakePaymentProvider` | feature | implementado |
| [004](004-stripe-provider.md) | `StripePaymentProvider` com circuit breaker | feature | implementado |
| [005](005-idempotencia-concorrente.md) | Idempotencia sob concorrencia | fix | implementado |
| [006](006-client-secret-e-campos-do-request.md) | `clientSecret` na resposta e `paymentMethod`/`description` no request | feature | implementado |
| [007](007-teste-integracao-controller.md) | Teste de integracao do controller (contexto completo + Postgres) | chore | implementado |
| [008](008-teste-de-carga-k6.md) | Teste de carga k6 (criacao, idempotencia, leitura) | feature | implementado |

## Planejado (ainda sem documento de feature)

Roadmap (README secao 10): v2 webhook do Stripe publicando no Kafka + consumer idempotente (`processed_webhook_events`); v3 outbox (poller de `payment_events` `PENDING_PUBLISH`); v4 restante: k6 de webhook em rajada; v5 deploy AWS; v6 opcional (segundo provider, split, recorrencia).

Crie o documento da feature quando ela entrar em desenvolvimento e adicione a linha na tabela.
