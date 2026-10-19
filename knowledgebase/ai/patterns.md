# Padroes

## Codigo novo / mudanca de contrato HTTP

- DTOs de entrada e saida como `record` em `api/dto`, com Bean Validation e `@Schema` (exemplos no Swagger).
- Controller fino: so HTTP (status, headers, `Location`); regra de negocio em `application/service`.
- Anotacoes `@Operation`/`@ApiResponses` no controller com todos os status possiveis.
- Erro novo = excecao em `domain/exception` + handler em `GlobalExceptionHandler` (corpo `ApiErrorResponse`, mensagem sem stack e sem dado do provider).
- Mensagens e comentarios em portugues, sem acento (padrao do repositorio).

## Provider

- Dominio e `PaymentService` dependem so de `application/port/PaymentProvider`; nunca importar `com.stripe.*` fora de `infrastructure/provider/stripe`.
- Adapter novo: classe em `infrastructure/provider/<nome>`, bean em `PaymentProviderConfig` com `@ConditionalOnProperty(name = "payment.provider", havingValue = "<nome>")`, teste unitario do adapter.
- Falha de infraestrutura -> `PaymentProviderException` (vira 502). Resultado de negocio (recusa) -> status no `ProviderPayment`, nao excecao (ADR-007).
- Propriedades do adapter em `@ConfigurationProperties` (record). Validacao de segredo obrigatorio no bean do adapter, **nao** no construtor do record (o record e sempre instanciado, mesmo com outro provider ativo).

## Dinheiro e estado

- Valor em centavos (`Long`); nunca decimal (ADR-001).
- Mudanca de status so por `PaymentService.transition`/`assertTransition`; nao setar `status` direto.
- Toda mudanca de estado grava `PaymentEvent` na mesma transacao (`recordEvent`) — ADR-005.
- Estorno: validar estado e saldo **antes** de chamar o provider; lock pessimista no pagamento (ADR-004).

## Idempotencia e concorrencia

- Unicidade garantida pelo banco; nao confiar em "consulta antes de inserir" (ADR-003).
- Quando precisar reagir a violacao de constraint, a releitura e feita **fora** da transacao abortada (`TransactionTemplate`).
- Valor enviado a API externa com idempotency key deve ser deterministico por chave.

## Migrations (Flyway)

- Nova migration = proximo `V<n>__descricao.sql`; **nunca editar** migration ja aplicada.
- Migration que muda dado existente inclui o `UPDATE` de conversao (ex.: V4 migrou `REFUNDED` antigos).
- `spring.jpa.hibernate.ddl-auto=validate`: entidade e schema precisam bater.

## Testes

- Unitarios (Mockito) para service e adapters; integracao com **Postgres real** (Testcontainers, `PostgresTestConfiguration`) quando o comportamento depende do banco (constraint, lock, concorrencia).
- Teste de corrida usa N threads com `CountDownLatch` de largada simultanea; ver `PaymentIdempotencyRaceTest`.
- Teste de Stripe nao chama a rede: mocka `StripeClient`/servicos (Mockito inline).
- Ao mudar comportamento, ajuste ou crie teste; nao conclua sem rodar (ver [[operations|Operacao]]).

## Segredos

- Chave do Stripe, senha do banco e CAs de rede ficam em `.env`/arquivos locais ignorados pelo Git. Nunca em codigo, log, README ou mensagem de commit.
- Variavel nova entra em `.env.example` (sem valor) e em [[environment|Ambiente]].
