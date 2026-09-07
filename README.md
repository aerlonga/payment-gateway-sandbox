# payment-gateway-sandbox# Payment Gateway Sandbox — Especificação Técnica

> Documento de especificação funcional e técnica do projeto. Serve como guia de implementação, do primeiro ao último endpoint, e como README principal do repositório.

---

## 1. Visão Geral

### 1.1 O que é

Uma API de processamento de pagamentos em **Java 21 + Spring Boot 3**, que integra com um gateway real em modo sandbox (Stripe), processa confirmações via webhook, desacopla o processamento pesado através de **Kafka**, e é implantada na **AWS** (EC2 + RDS).

### 1.2 Por que esse projeto existe

Demonstrar competência prática em:
- Modelagem de domínio financeiro (dinheiro, estados de transação, idempotência)
- Integração com API externa real (autenticação, webhooks, validação de assinatura)
- Arquitetura orientada a eventos (Kafka, outbox pattern)
- Observabilidade e resiliência (Actuator, Resilience4j)
- Teste de carga (k6)
- Deploy em nuvem (AWS free tier) com consciência de trade-offs de custo

### 1.3 Escopo (o que este projeto NÃO é)

Não é um gateway de pagamento de verdade. Não processa dinheiro real. Não deve ser usado em produção. Todo o processamento financeiro real acontece do lado do Stripe (modo sandbox); esta aplicação é o **orquestrador** ao redor dele.

---

## 2. Arquitetura Geral

```
                     ┌─────────────────────┐
                     │      Cliente         │
                     │ (Postman/Front/etc)  │
                     └──────────┬───────────┘
                                │ HTTPS
                                ▼
                     ┌─────────────────────┐
                     │   Payment API        │
                     │  (Spring Boot)       │
                     │  EC2 t2/t3.micro     │
                     └──────┬───────┬───────┘
                            │       │
                 cria/consulta      │ publica evento
                    cobrança        │ (payment.created,
                            │       │  payment.confirmed,
                            ▼       │  payment.failed)
                     ┌──────────┐   │
                     │  Stripe  │   │
                     │ (Sandbox)│   ▼
                     └────┬─────┘  ┌─────────────────┐
                          │        │      Kafka        │
                          │webhook │  (Docker na EC2   │
                          ▼        │  ou instância      │
                     ┌──────────┐  │  separada)         │
                     │ /webhooks│  └────────┬───────────┘
                     │  /stripe │           │
                     └────┬─────┘           │ consome
                          │                 ▼
                          │        ┌─────────────────────┐
                          └───────▶│  Payment Consumer   │
                                   │  (processa evento,   │
                                   │   atualiza estado,    │
                                   │   idempotente)         │
                                   └──────────┬────────────┘
                                              ▼
                                   ┌─────────────────────┐
                                   │   RDS (Postgres)     │
                                   │   free tier          │
                                   └─────────────────────┘
```

**Fluxo resumido:**
1. Cliente chama `POST /api/payments` → API cria registro local (status `PENDING`) e chama o Stripe.
2. Stripe processa (sandbox) e, de forma assíncrona, chama nosso `POST /api/webhooks/stripe`.
3. O endpoint de webhook valida a assinatura HMAC, **não processa nada pesado**, apenas publica um evento no Kafka e responde `200` rapidamente (Stripe tem timeout curto e reenvia se demorar).
4. Um consumer Kafka separado lê o evento, atualiza o status da cobrança no Postgres, de forma idempotente (evita processar o mesmo evento duas vezes).

---

## 3. Modelagem de Dados

### 3.1 Entidade `Payment`

| Campo | Tipo | Observação |
|---|---|---|
| id | UUID | PK |
| customer_id | UUID | referência ao cliente (pode ser tabela simplificada `Customer`) |
| amount | `BIGINT` (centavos) | **nunca usar `double`/`float` para dinheiro** |
| currency | VARCHAR(3) | ex: `BRL`, `USD` |
| status | ENUM | `PENDING`, `AUTHORIZED`, `CAPTURED`, `FAILED`, `REFUNDED`, `CANCELED` |
| provider | VARCHAR | `stripe` |
| provider_payment_id | VARCHAR | id retornado pelo Stripe (`pi_xxx`) |
| idempotency_key | VARCHAR, UNIQUE | chave enviada pelo cliente no header |
| created_at / updated_at | TIMESTAMP | |

### 3.2 Entidade `PaymentEvent` (auditoria / outbox)

| Campo | Tipo | Observação |
|---|---|---|
| id | UUID | PK |
| payment_id | UUID | FK |
| event_type | VARCHAR | `payment.created`, `payment.confirmed`, `payment.failed`, `payment.refunded` |
| payload | JSONB | corpo do evento |
| status | ENUM | `PENDING_PUBLISH`, `PUBLISHED`, `FAILED` (para o outbox pattern) |
| created_at | TIMESTAMP | |

> Essa tabela serve tanto como **auditoria/histórico** (endpoint `GET /api/payments/{id}/history`) quanto como base do **outbox pattern**: a gravação do evento acontece na mesma transação do save do `Payment`, e um processo separado (poller ou Debezium, se quiser ir fundo) lê essa tabela e publica no Kafka — isso evita o problema clássico de "salvei no banco mas o Kafka caiu antes de publicar".

### 3.3 Entidade `ProcessedWebhookEvent` (idempotência de consumo)

| Campo | Tipo | Observação |
|---|---|---|
| id | UUID | PK |
| stripe_event_id | VARCHAR, UNIQUE | id do evento vindo do Stripe (`evt_xxx`) |
| processed_at | TIMESTAMP | |

> Antes de processar um webhook, verifica-se se `stripe_event_id` já existe aqui. Se sim, ignora (idempotência). O Stripe reenvia webhooks se não receber `200` a tempo, então isso é essencial.

### 3.4 (Opcional — se evoluir pra split de pagamento)

- `Merchant` (lojista) — saldo, taxa de comissão
- `PaymentSplit` — payment_id, merchant_id, valor destinado, percentual de taxa da plataforma

---

## 4. Endpoints — Especificação Detalhada

### 4.1 `POST /api/payments`

Cria uma cobrança.

**Headers obrigatórios:**
- `Idempotency-Key: <string>` — evita criar duas cobranças iguais em caso de retry do cliente

**Request body:**
```json
{
  "customerId": "uuid",
  "amount": 15000,
  "currency": "BRL",
  "paymentMethod": "card",
  "description": "Pedido #1234"
}
```

**Regras de negócio:**
- `amount` sempre em centavos, inteiro.
- Se `Idempotency-Key` já existe no banco, retorna o **mesmo resultado** da primeira chamada (não cria duplicado) — status `200`, não `201`.
- Cria `Payment` local com status `PENDING`, chama Stripe (`PaymentIntent.create`), salva `provider_payment_id`.
- Grava evento `payment.created` na tabela de outbox.

**Responses:**
- `201 Created` — cobrança criada, retorna objeto `Payment` + `clientSecret` (necessário se o front for confirmar o pagamento do lado do Stripe)
- `400 Bad Request` — validação (amount <= 0, moeda inválida etc.)
- `409 Conflict` — se preferir tratar idempotency key duplicada com payload diferente como erro, ao invés de retornar o resultado anterior

---

### 4.2 `GET /api/payments/{id}`

Consulta uma cobrança específica.

- `200 OK` com o objeto `Payment`
- `404 Not Found` se não existir

---

### 4.3 `GET /api/payments`

Lista/filtra cobranças.

**Query params:** `customerId`, `status`, `page`, `size`, `sort`

- `200 OK` — paginado (Spring Data `Pageable`)
- Importante para o teste de carga de leitura (avaliar índice em `customer_id` + `status`)

---

### 4.4 `POST /api/payments/{id}/refund`

Estorna uma cobrança (total ou parcial).

**Request body:**
```json
{
  "amount": 5000,
  "reason": "requested_by_customer"
}
```

**Regras:**
- Só permite estorno se status atual for `CAPTURED`.
- Se `amount` omitido, estorna o valor total restante.
- Chama Stripe `Refund.create`, atualiza status para `REFUNDED` (ou mantém `CAPTURED` com estorno parcial registrado, dependendo de como você quer modelar).
- Gera evento `payment.refunded`.

**Responses:** `200 OK`, `400` (regra de negócio violada), `404`, `409` (estado inválido pra estorno)

---

### 4.5 `POST /api/payments/{id}/cancel`

Cancela uma cobrança antes da captura (status `PENDING` ou `AUTHORIZED`).

- `200 OK`, `409 Conflict` se já estiver `CAPTURED`/`REFUNDED`

---

### 4.6 `GET /api/payments/{id}/history`

Retorna a lista de eventos (`PaymentEvent`) daquela cobrança — auditoria de mudança de estado.

- `200 OK` com array ordenado por `created_at`

---

### 4.7 `POST /api/webhooks/stripe`

Endpoint que o Stripe chama. **Não é para ser chamado manualmente pelo cliente da API.**

**Regras críticas:**
1. Valida a assinatura HMAC do header `Stripe-Signature` contra o payload bruto (raw body — atenção: não pode passar por desserialização antes da validação).
2. Verifica se `stripe_event_id` já foi processado (tabela `ProcessedWebhookEvent`). Se sim, retorna `200` sem reprocessar.
3. Publica o evento no tópico Kafka correspondente.
4. Responde `200` o mais rápido possível (não faça processamento síncrono pesado aqui).

**Responses:**
- `200 OK` — sempre que a assinatura for válida (mesmo que o evento já tenha sido processado)
- `400 Bad Request` — assinatura inválida

---

### 4.8 Endpoints de assinatura/recorrência (fase opcional de evolução)

- `POST /api/subscriptions` — cria plano + assinatura no Stripe
- `POST /api/subscriptions/{id}/cancel`
- `GET /api/subscriptions/{id}/invoices`

---

### 4.9 Observabilidade

- `GET /actuator/health` — liveness/readiness
- `GET /actuator/metrics` — métricas (usadas em conjunto com o teste de carga)
- `GET /actuator/prometheus` (se adicionar `micrometer-registry-prometheus`) — para scraping, caso queira montar um Grafana depois

---

## 5. Kafka — Tópicos e Contrato de Eventos

### 5.1 Tópicos

| Tópico | Produtor | Consumidor | Partições sugeridas |
|---|---|---|---|
| `payment.created` | Payment API | Consumer de auditoria/notificação | 3 |
| `payment.confirmed` | Webhook handler | Payment Consumer (atualiza status) | 3 |
| `payment.failed` | Webhook handler | Payment Consumer | 3 |
| `payment.refunded` | API (refund endpoint) | Payment Consumer | 3 |

### 5.2 Contrato de mensagem (exemplo `payment.confirmed`)

```json
{
  "eventId": "evt_stripe_xxx",
  "paymentId": "uuid",
  "providerPaymentId": "pi_xxx",
  "status": "CAPTURED",
  "amount": 15000,
  "currency": "BRL",
  "occurredAt": "2026-09-07T14:30:00Z"
}
```

- **Chave da mensagem (key):** `paymentId` — garante que eventos do mesmo pagamento vão pra mesma partição, preservando ordem.
- **Idempotência no consumer:** antes de aplicar a mudança de status, verificar se aquele `eventId` já foi processado (mesma tabela `ProcessedWebhookEvent` mencionada acima, ou uma nova exclusiva para consumo).

### 5.3 Outbox Pattern (evolução v3)

Ao invés do endpoint de webhook publicar diretamente no Kafka, ele grava o evento na tabela `PaymentEvent` (status `PENDING_PUBLISH`) na mesma transação do banco. Um poller (ou Debezium com CDC, se quiser se aprofundar) lê essa tabela periodicamente e publica no Kafka, marcando como `PUBLISHED`. Isso garante atomicidade entre "salvar no banco" e "publicar evento" — problema clássico em sistemas distribuídos.

---

## 6. Segurança e Resiliência

- **Validação de webhook:** HMAC SHA-256 usando o `webhook signing secret` do Stripe (biblioteca oficial `stripe-java` já tem utilitário pronto pra isso).
- **Idempotência de escrita:** header `Idempotency-Key` em `POST /api/payments`.
- **Idempotência de consumo:** tabela de eventos processados (webhook e Kafka).
- **Circuit breaker / retry:** usar **Resilience4j** ao redor da chamada para a API do Stripe — se o Stripe estiver lento/indisponível, o circuito abre e falha rápido ao invés de travar threads.
- **Rate limiting (opcional):** bucket4j ou similar no endpoint de criação de pagamento.
- **Segredos:** nunca commitar API keys — usar variáveis de ambiente / AWS Secrets Manager na fase de deploy.

---

## 7. Teste de Carga

Ferramenta: **k6**.

### 7.1 Cenários

1. **Criação de cobrança sob concorrência**
   - N usuários virtuais, cada um enviando `POST /api/payments` com `Idempotency-Key` diferente.
   - Variante extra: repetir a **mesma** `Idempotency-Key` simultaneamente de propósito, pra validar que a idempotência segura mesmo sob concorrência (race condition clássica — precisa de constraint `UNIQUE` no banco + tratamento de exceção, não só verificação em memória).

2. **Webhook em rajada**
   - Simular o Stripe mandando muitos webhooks de uma vez (script k6 chamando `/api/webhooks/stripe` com payloads e assinaturas válidas pré-geradas).
   - Medir: o endpoint responde rápido (só publica no Kafka) mesmo sob carga? Ou trava por processar de forma síncrona?

3. **Leitura pesada**
   - `GET /api/payments?customerId=X` com grande volume de dados populado previamente (seed de dados).
   - Comparar latência com e sem índice composto `(customer_id, status)`.

### 7.2 Métricas-alvo

- p95 e p99 de latência
- Taxa de erro (`http_req_failed`)
- Throughput (req/s sustentado)

---

## 8. Deploy AWS (Free Tier)

### 8.1 Componentes

| Componente | Serviço AWS | Observação |
|---|---|---|
| API Spring Boot | EC2 (t2.micro/t3.micro) | free tier primeiro ano |
| Banco de dados | RDS Postgres (t2/t3.micro) | free tier primeiro ano |
| Kafka | Docker Compose na própria EC2 (ou EC2 separada) | **MSK não entra no free tier de forma prática** — rodar standalone é a opção viável |
| Deploy | Elastic Beanstalk ou EC2 manual com Docker | Fargate free tier é limitado, EC2 direto é mais previsível |
| Domínio/HTTPS (opcional) | Route53 + ACM | custo pequeno no domínio |

### 8.2 Justificativa a documentar no README

Explicitar que, em um cenário de produção real, o ideal seria usar **Amazon MSK** ou **Confluent Cloud** para o Kafka gerenciado, mas que a escolha de rodar standalone em container foi consciente, por conta do custo em ambiente de portfólio/estudo. Esse tipo de nota demonstra entendimento de trade-off, não só execução.

### 8.3 Ordem de implantação

1. RDS Postgres free tier
2. EC2 com a aplicação Spring Boot (via Docker)
3. Kafka + Zookeeper (ou modo KRaft, sem Zookeeper) em container na mesma EC2 ou em uma instância adicional
4. Variáveis de ambiente / secrets via `.env` ou AWS Systems Manager Parameter Store
5. (Opcional) domínio + HTTPS

---

## 9. Estrutura de Pastas

```
payment-gateway-sandbox/
├── src/main/java/com/seuprojeto/payments/
│   ├── api/                  # controllers
│   ├── domain/                # entidades, enums
│   ├── application/           # services, casos de uso
│   ├── infrastructure/
│   │   ├── stripe/            # client Stripe
│   │   ├── kafka/              # producers/consumers
│   │   └── persistence/        # repositories
│   ├── config/                 # security, resilience4j, kafka config
│   └── PaymentGatewayApplication.java
├── src/test/java/...           # testes unitários e de integração (Testcontainers p/ Postgres + Kafka)
├── load-tests/
│   └── k6/
│       ├── create-payment.js
│       ├── webhook-burst.js
│       └── read-heavy.js
├── docker-compose.yml           # Postgres + Kafka local pra desenvolvimento
├── docs/
│   └── architecture-diagram.png
└── README.md
```

---

## 10. Roadmap Evolutivo

| Fase | Entrega | Objetivo |
|---|---|---|
| v1 | Endpoints core (4.1 a 4.6) + integração síncrona com Stripe sandbox, sem Kafka | Provar domínio de pagamento e integração externa |
| v2 | Webhook publica em Kafka, consumer processa de forma assíncrona | Provar mensageria e desacoplamento |
| v3 | Outbox pattern + idempotência refinada no consumer | Provar maturidade em sistemas distribuídos |
| v4 | Teste de carga completo (k6) documentado no README com resultados | Provar preocupação com performance |
| v5 | Deploy AWS free tier (EC2 + RDS + Kafka em container) | Provar capacidade de subir e operar em nuvem |
| v6 (opcional) | Split de pagamento (marketplace) ou recorrência/assinaturas | Expandir domínio de negócio |

---

## 11. Stack Técnica Resumida

- **Linguagem:** Java 21
- **Framework:** Spring Boot 3 (Web, Data JPA, Validation, Actuator)
- **Banco:** PostgreSQL
- **Mensageria:** Apache Kafka
- **Resiliência:** Resilience4j
- **Gateway de pagamento:** Stripe (Java SDK, modo sandbox)
- **Teste de carga:** k6
- **Testes:** JUnit 5, Testcontainers (Postgres + Kafka em container nos testes de integração)
- **Deploy:** Docker, AWS (EC2, RDS)