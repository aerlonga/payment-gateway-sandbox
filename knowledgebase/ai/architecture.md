# Arquitetura

## Fluxo de dados

```
Cliente (Swagger/Postman/k6)
  -> POST /api/v1/payments (Idempotency-Key)
  -> PaymentController -> PaymentService
       -> PaymentRepository (Postgres)        # Payment PENDING + payment_events
       -> PaymentProvider (porta)             # Stripe OU Fake, escolhido por payment.provider
  <- 201 (criado) | 200 (replay idempotente)
```

Planejado (v2/v3, ainda nao existe): webhook do Stripe -> publica no Kafka -> consumer atualiza o status; poller le `payment_events` `PENDING_PUBLISH` e publica no Kafka (outbox). Ver ADR-006.

## Codigo da aplicacao

Pacote base: `com.aerlon.payment_gateway_sandbox`.

| Area | Caminho (`src/main/java/.../payment_gateway_sandbox`) |
|------|------|
| Controllers + DTOs + erros HTTP | `api/controller`, `api/dto`, `api/exception/GlobalExceptionHandler` |
| Casos de uso | `application/service/PaymentService`, `CreatePaymentResult` |
| Mapeamento entidade <-> DTO | `application/mapper/PaymentMapper` |
| Porta do gateway | `application/port/` (`PaymentProvider`, `CreatePaymentCommand`, `RefundCommand`, `ProviderPayment`, `ProviderRefund`) |
| Dominio | `domain/entities` (`Payment`, `PaymentEvent`), `domain/enums`, `domain/exception` |
| Persistencia | `infrastructure/persistence` (repositorios Spring Data) |
| Adapters de provider | `infrastructure/provider/fake`, `infrastructure/provider/stripe` |
| Selecao do provider | `config/PaymentProviderConfig` (`@ConditionalOnProperty payment.provider`) |
| Swagger | `config/OpenApiConfig` |
| Migrations | `src/main/resources/db/migration` (V1..V5) |

## Endpoints (prefixo `/api/v1/payments`)

| Metodo | Rota | Respostas |
|---|---|---|
| POST | `/` (header `Idempotency-Key`; body com `paymentMethod` e `description` opcionais) | 201 criado (com `clientSecret`), 200 replay, 400, 409 chave com payload diferente, 502 provider |
| GET | `/{id}` | 200, 404 |
| GET | `/?customerId=&status=&page=&size=&sort=` | 200 paginado (lista vazia e 200, nao 404) |
| POST | `/{id}/cancel` | 200, 404, 409 |
| POST | `/{id}/refund` | 200, 400 valor acima do saldo, 404, 409 nao `CAPTURED` |
| GET | `/{id}/history` | 200 eventos por `created_at`, 404 |

O README descreve os paths como `/api/payments`; a implementacao usa `/api/v1/payments`.

## Erros HTTP (`GlobalExceptionHandler`)

`PaymentNotFoundException` 404 · `InvalidPaymentStateException` e `IdempotencyConflictException` 409 · `InvalidRefundException` 400 · validacao 400 · `PaymentProviderException` 502 (stack so no log) · inesperado 500.

## Maquina de estados do pagamento

```
PENDING ──► AUTHORIZED ──► CAPTURED ──► REFUNDED
   │  │          │
   │  └─────────►┘ (PENDING -> CAPTURED direto: provider com captura imediata)
   ├──► FAILED   ├──► FAILED
   └──► CANCELED └──► CANCELED
```

Transicoes validadas em `PaymentService.ALLOWED_TRANSITIONS`. `FAILED`, `REFUNDED`, `CANCELED` sao finais. Estorno parcial **nao** muda o status: o pagamento fica `CAPTURED` ate `refunded_amount == amount` (ADR-004).

## Modelo de dados

| Tabela | Guarda |
|---|---|
| `payments` | `id`, `customer_id` (referencia solta, sem tabela de cliente), `amount` (centavos), `currency`, `status`, `provider` ("fake"/"stripe"), `provider_payment_id`, `payment_method` e `description` (opcionais, do request), `refunded_amount`, `idempotency_key` (`UNIQUE`), timestamps. `CHECK (0 <= refunded_amount <= amount)` |
| `payment_events` | auditoria + outbox: `event_type` (`payment.created/authorized/confirmed/failed/canceled/refunded`), `payload` JSONB, `status` (`PENDING_PUBLISH`/`PUBLISHED`/`FAILED`) |
| `processed_webhook_events` | idempotencia do webhook (`stripe_event_id` `UNIQUE`); tabela criada, **sem codigo ainda** |

Indice `(customer_id, status)` em `payments` para a listagem.

## Provider

`PaymentProvider` (porta) com `createPayment`, `getPayment`, `refund`, `cancel`, `name()`. Escolha por `payment.provider` (`fake` | `stripe`); default `fake` (ver [[environment|Ambiente]]).

| Adapter | Comportamento |
|---|---|
| `FakePaymentProvider` | sem rede; latencia aleatoria `[min,max]` ms; `failure-rate` recusa (status `FAILED`); aprovacao ja vem `CAPTURED`; ids `pi_fake_*`, `re_fake_*` |
| `StripePaymentProvider` | `PaymentIntent.create` com `Idempotency-Key` escopada por cliente; com `payment-method` de teste confirma e captura na criacao, sem ele fica `PENDING`; recusa de cartao vira `FAILED`; falha de rede/circuito aberto vira `PaymentProviderException` |

Mapeamento de status do `PaymentIntent`: `succeeded`->`CAPTURED`, `requires_capture`->`AUTHORIZED`, `canceled`->`CANCELED`, demais->`PENDING`.

## Concorrencia

- **Idempotencia:** `saveAndFlush` do pagamento antes de chamar o provider; a constraint `UNIQUE` decide quem cria; a perdedora tem a violacao capturada e relida fora da transacao abortada (ADR-003). `PaymentService.create` usa `TransactionTemplate`, nao `@Transactional`.
- **Estorno:** `findByIdForUpdate` (lock pessimista) serializa estornos do mesmo pagamento (ADR-004).
- Chamada ao provider ocorre **dentro** da transacao de criacao: segura uma conexao do pool durante a latencia do provider. Custo conhecido, a medir no k6 (ADR-006).

## Containers (`docker-compose.yml`)

| Servico | Papel |
|---|---|
| `postgres` | Postgres 16 (:5432), volume `postgres_data` |
| `kafka` | `apache/kafka:3.9.0`, KRaft; `kafka:29092` entre containers, `localhost:9092` do host |
| `app` | a aplicacao (:8080); espera Postgres e Kafka healthy |
