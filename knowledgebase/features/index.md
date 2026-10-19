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

## Planejado (ainda sem documento de feature)

Fechar a v1: `clientSecret` na resposta do `POST`, `paymentMethod`/`description` no request, testes de controller (MockMvc).

Roadmap (README secao 10): v2 webhook do Stripe publicando no Kafka + consumer idempotente (`processed_webhook_events`); v3 outbox (poller de `payment_events` `PENDING_PUBLISH`); v4 teste de carga k6 documentado; v5 deploy AWS; v6 opcional (segundo provider, split, recorrencia).

Crie o documento da feature quando ela entrar em desenvolvimento e adicione a linha na tabela.
