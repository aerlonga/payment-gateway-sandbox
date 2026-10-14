# Notas de estudo e estado do projeto

Resumo da sessao de 14/10/2026. Serve pra retomar o trabalho em outra maquina.

## Onde paramos

Branch: `feat/refund-history-swagger`

Commits recentes:
- `550b872` endpoint de refund, historico de eventos e config do swagger
- `7d50117` dockerfile e servico da aplicacao no compose, listener interno do kafka
- ultimo: `@ParameterObject` na listagem + este arquivo

Pendente: abrir PR da branch pra `main` e fazer o merge (feito manualmente, `gh` nao esta instalado).

## Como rodar tudo via Docker

```bash
docker compose up -d --build
```

Sobe 3 containers: `postgres` (5432), `kafka` (9092 pro host, `kafka:29092` entre containers) e `app` (8080).
A app espera Postgres e Kafka ficarem healthy antes de iniciar.

- Swagger UI: http://localhost:8080/swagger-ui.html
- OpenAPI JSON: http://localhost:8080/v3/api-docs
- Depois de mudar codigo: `docker compose up -d --build app`
- Zerar o banco: `docker compose down -v` (o volume `postgres_data` persiste entre `down`/`up`)

Rodar sem container da app (hot reload, debug): suba so `postgres` e `kafka` e use `./mvnw spring-boot:run`.

Precisa de um `.env` (copie de `.env.example`). Ele esta no `.gitignore`, entao nao vem no clone.

### Por que o Docker aqui

Postgres e Kafka em container = infra descartavel, com versao fixa, igual em qualquer maquina. A app tambem tem Dockerfile pra subir tudo com um comando. Dev loop com IDE continua possivel rodando a app fora do container.

## Kafka no compose

- Dois listeners: `PLAINTEXT://kafka:29092` (entre containers) e `EXTERNAL://localhost:9092` (do host).
- `CLUSTER_ID`, `NODE_ID`, `QUORUM_VOTERS` etc. nao sao credenciais: sao identidade e topologia.
- Tudo em `PLAINTEXT`: sem autenticacao nem criptografia. Ok pra sandbox. Em producao seria `SASL_SSL`, com credenciais no secret manager.
- A app ainda nao usa Kafka. So existem o enum `EventPublishStatus` (`PENDING_PUBLISH`) e a entidade `PaymentEvent`, preparando um outbox. Falta o publisher.
- Credenciais reais do projeto: `DB_USER`/`DB_PASSWORD` no `.env`. Chave da Stripe tambem iria la.

## Como testar pelo Swagger

Dados de exemplo:
- `customerId`: `3f5b1c2e-1d3a-4a1b-9c0d-2b7f8e6a1234` (e o `example` do DTO, nao existe tabela de cliente)
- `Idempotency-Key`: voce inventa (ex.: `teste-001` ou `uuidgen`). Quem gera e o cliente da API.

Fluxo:
1. `POST /api/v1/payments` com header `Idempotency-Key` e body:
   ```json
   {"customerId": "3f5b1c2e-1d3a-4a1b-9c0d-2b7f8e6a1234", "amount": 19990, "currency": "BRL", "provider": "STRIPE"}
   ```
2. `GET /api/v1/payments/{id}`: consulta um pagamento. Id inexistente = 404 `Pagamento nao encontrado`.
3. `GET /api/v1/payments?customerId=...`: lista pagamentos de um cliente.
4. `POST /api/v1/payments/{id}/cancel`
5. `POST /api/v1/payments/{id}/refund` com `{"amount": 5000, "reason": "Cliente desistiu"}` (sem `amount` = estorno total)
6. `GET /api/v1/payments/{id}/history`: eventos do pagamento.

Idempotencia:
- Mesma chave + mesmo body = devolve o pagamento original.
- Mesma chave + body diferente = conflito (`IdempotencyConflictException`).
- Pagamento novo = chave nova.

### Confusoes que apareceram

- `id` (do pagamento) e `customerId` (do cliente) sao campos diferentes. A listagem filtra por `customerId`. Colocar o `id` do pagamento la devolve lista vazia.
- Listagem com cliente sem pagamentos = **200 com `content: []`**, nao 404. E um filtro numa colecao; sem tabela de clientes, "cliente inexistente" e "cliente sem pagamentos" sao indistinguiveis. 404 so em `GET /{id}`.
- O bloco "Example Value" do Swagger (com `"string"`, `0`) e so exemplo do schema, nao resposta real.
- `pageable` aparecia como objeto JSON no Swagger e nao funcionava. Corrigido com `@ParameterObject` em `PaymentController.listByCustomer`; agora mostra `page`, `size` e `sort`.
- O banco nao estava vazio: havia 2 pagamentos de 02/10 (volume persistente).

## Maquina de estados do pagamento

```
PENDING ──► AUTHORIZED ──► CAPTURED ──► REFUNDED
   │            │
   ├──► FAILED  ├──► FAILED
   └──► CANCELED└──► CANCELED
```

- Todo pagamento novo nasce `PENDING` (`PaymentMapper.toEntity`).
- `FAILED`, `REFUNDED` e `CANCELED` sao finais.
- Nao existe endpoint pra ir de `PENDING` a `AUTHORIZED`/`CAPTURED`: isso viria do Stripe via webhook, que ainda nao existe.
- `refund` so e aceito em `CAPTURED`. Pra testar pela API, force o status:
  ```bash
  docker exec -it payment-gateway-postgres psql -U postgres -d payment_gateway \
    -c "update payments set status='CAPTURED' where id='<id>';"
  ```
- Refund: valor nao pode exceder o do pagamento. Ainda nao ha controle de saldo estornado: qualquer estorno (inclusive parcial) leva a `REFUNDED`.
- `providerPaymentId` fica `null` porque nao ha cliente Stripe.

## Conceitos discutidos

**Idempotency-Key:** string unica por tentativa de pagamento, gerada pelo cliente. Evita cobranca duplicada quando o cliente repete o request depois de falha de rede.

**Por que so `customerId` e nao o nome:** servico de pagamento guarda so a referencia. Nome e dados pessoais ficam no servico de clientes (ou no objeto `Customer` do Stripe). Motivos: responsabilidade unica, menos dado sensivel (LGPD/PCI-DSS), sem dado desatualizado. A tela busca o nome no outro servico usando o id.

**Monolito x microservicos:**
- Monolito com usuarios e pagamentos juntos e comum e muitas vezes a melhor escolha: mesma base, FK e `JOIN` trazem o nome junto.
- Microservicos: banco por servico, sem `JOIN`, so o id do outro servico. Custo: rede, consistencia, infra.
- Este projeto e um servico de pagamento isolado (Kafka + outbox), entao `customerId` como referencia solta e coerente.
- Regra pratica: comecar monolito modular e extrair servicos so com dor real.

## Proximos passos possiveis

- Abrir PR e fazer merge na `main`.
- Cliente Stripe (preencher `providerPaymentId`) e webhooks (`PENDING` → `AUTHORIZED`/`CAPTURED`); a tabela `processed_webhook_events` ja existe na migration V3.
- Publisher Kafka lendo os eventos `PENDING_PUBLISH` (outbox).
- Instalar `gh` (GitHub CLI) pra automatizar PR e merge.
