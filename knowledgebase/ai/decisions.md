# Decisoes (ADRs)

## ADR-001 — Dinheiro em centavos (2026-10-14)

- **Decisao:** todo valor monetario e `Long` em centavos (`BIGINT` no banco, `CHECK (amount > 0)`); moeda ISO-4217 em 3 letras maiusculas.
- **Motivo:** `double`/`float` perdem precisao; o Stripe tambem trabalha na menor unidade da moeda.
- **Consequencias:** DTOs, entidade e comandos do provider usam `Long`/`long`; a API nunca recebe decimal.

## ADR-002 — Abstracao de provider (porta/adapter) e `FakePaymentProvider` (2026-10-19)

- **Decisao:** toda comunicacao com o gateway passa pela interface `PaymentProvider` (`application/port`). Adapters ficam em `infrastructure/provider/` (`stripe`, `fake`), escolhidos por `payment.provider`. O `provider` **sai do request** e passa a ser configuracao; `payments.provider` guarda o `name()` do adapter.
- **Motivo:** o sandbox do Stripe tem rate limit bem abaixo do de producao e inviabiliza teste de carga (README secao 7); o fake permite medir a aplicacao e nao o limite do Stripe, alem de testes rapidos e deterministicos. Tambem deixa a troca/adicao de gateway restrita a uma classe nova.
- **Consequencias:**
  - O enum antigo `PaymentProvider { STRIPE, MOCK }` foi removido (colidia com o nome da interface); `Payment.provider` virou `String`.
  - Default `payment.provider=fake` (profile `loadtest` com latencia 200-400 ms e 2% de falha). Para usar o Stripe: `PAYMENT_PROVIDER=stripe`.
  - O fake aprova ja como `CAPTURED` (nao ha webhook), o que torna o refund testavel sem forcar status no banco; por isso `PENDING -> CAPTURED` foi liberada na maquina de estados.
  - O fake nao reproduz a idempotencia do Stripe: o caminho "dois workers chamam o provider" nao aparece no teste de carga.
  - Numeros do rate limit do sandbox: conferir em `docs.stripe.com/rate-limits` antes de citar no portfolio (o README fala em ~25 ops/s no sandbox).

## ADR-003 — Idempotencia pelo banco, tambem sob concorrencia (2026-10-19)

- **Decisao:** `Idempotency-Key` e `UNIQUE` em `payments`. O insert e enviado ao banco (`saveAndFlush`) **antes** de chamar o provider. Quem perde a corrida toma `DataIntegrityViolationException`, relê o pagamento **fora** da transacao abortada e responde como replay (200, mesmo payload) ou conflito (409, payload diferente).
- **Motivo:** o fluxo "consulta e depois insere" deixava a perdedora estourar a constraint no commit (500), depois de ja ter chamado o provider. Reproduzido por teste com 16 threads antes da correcao.
- **Consequencias:**
  - `PaymentService.create` usa `TransactionTemplate` (a releitura precisa estar fora da transacao que o Postgres abortou); nao voltar para `@Transactional`.
  - A perdedora fica bloqueada, com uma conexao do pool, ate a vencedora dar commit. Com o fake e pouco; com o Stripe real, uma rajada com a **mesma** chave pode esgotar o pool. Ver ADR-006.
  - Ao Stripe vai a chave `customerId:Idempotency-Key`, e os metadados do `PaymentIntent` so levam valores deterministicos por chave (`idempotencyKey`, `customerId`). O `paymentId` ficou de fora: muda a cada tentativa e o Stripe rejeita a mesma chave com parametros diferentes.
  - Testes: `PaymentIdempotencyRaceTest` (Postgres real via Testcontainers).

## ADR-004 — Estorno parcial com `refunded_amount` (2026-10-19)

- **Decisao:** `payments.refunded_amount` acumula o que ja foi estornado (migration V4, `CHECK (0 <= refunded_amount <= amount)`). Estorno so em `CAPTURED`, validado contra o saldo `amount - refunded_amount`; segue `CAPTURED` enquanto houver saldo e vira `REFUNDED` quando o total e estornado. `amount` omitido estorna o saldo. Cada estorno grava `payment.refunded` com `refundedAmount`, `totalRefunded` e `remaining`.
- **Motivo:** o modelo anterior levava qualquer estorno a `REFUNDED` (estado final), entao um estorno parcial bloqueava os seguintes e mostrava "tudo devolvido" quando nao era.
- **Consequencias:**
  - Alternativa descartada: status `PARTIALLY_REFUNDED` (mexeria na maquina de estados e nos eventos).
  - `PaymentRepository.findByIdForUpdate` (lock pessimista) serializa estornos concorrentes; o lock fica preso durante a chamada ao provider.
  - A migration converteu pagamentos `REFUNDED` antigos para `refunded_amount = amount`.
  - O estorno ao Stripe nao tem Idempotency-Key propria ainda.

## ADR-005 — `payment_events` como auditoria e base do outbox (2026-10-14)

- **Decisao:** toda mudanca de estado grava um `PaymentEvent` (`PENDING_PUBLISH`) na **mesma transacao** da mudanca; serve de historico (`GET /{id}/history`) e de base do outbox.
- **Motivo:** evita o problema "salvei no banco mas o Kafka caiu antes de publicar".
- **Consequencias:** ainda nao ha publisher; os eventos acumulam em `PENDING_PUBLISH`. O poller e a v3 do roadmap.

## ADR-006 — Criacao sincrona; Kafka para webhook e outbox (2026-10-19)

- **Decisao:** `POST /payments` continua **sincrono** (chama o provider e responde 201/200). O Kafka entra na confirmacao do resultado (webhook -> topico -> consumer) e na publicacao de eventos (outbox), nao na criacao da cobranca.
- **Motivo:** o `POST` devolve o `clientSecret` do `PaymentIntent`, que o front precisa na hora para confirmar com Stripe.js; criacao assincrona (202 + polling) mudaria o contrato e exigiria estado "aceito, nao enviado". E o padrao recomendado pelo Stripe.
- **Consequencias:**
  - Criacao assincrona fica como evolucao opcional, a decidir **com numeros do k6**. Se o pool de conexoes virar gargalo, a opcao intermediaria e gravar `PENDING` e fechar a transacao antes de chamar o provider, aplicando o resultado em outra transacao, com um reconciliador de `PENDING` antigos (`getPayment` ja existe na porta).
  - Qualquer worker concorrente precisaria de `SELECT ... FOR UPDATE SKIP LOCKED` mais a Idempotency-Key do provider.
  - Timeout do Stripe hoje vira 502 e rollback; o provider pode ter processado. Reconciliacao fica junto do webhook (v2).

## ADR-007 — Recusa de cartao e resultado de negocio, nao falha do provider (2026-10-19)

- **Decisao:** `CardException` do Stripe vira pagamento `FAILED` (nao excecao) e **nao conta** no circuit breaker (`ignoreExceptions(CardException.class)` em `PaymentProviderConfig.stripeCircuitBreaker()`). Erro de rede, timeout ou circuito aberto vira `PaymentProviderException` -> 502.
- **Motivo:** uma sequencia de cartoes recusados abriria o circuito e derrubaria pagamentos validos (achado de revisao de seguranca).
- **Consequencias:** o retry de rede do SDK (`max-network-retries=2`) e seguro no create por causa da Idempotency-Key; refund e cancel nao tem retry proprio.

## ADR-008 — Sem co-autoria nos commits (2026-10-14)

- **Decisao:** nunca adicionar `Co-Authored-By` (nem atribuicao a Claude/agentes) em commits ou descricoes de PR deste repositorio.
- **Motivo:** pedido explicito do dono do projeto.
- **Consequencias:** vale para qualquer agente, mesmo que a ferramenta sugira o trailer; a regra do projeto prevalece.
