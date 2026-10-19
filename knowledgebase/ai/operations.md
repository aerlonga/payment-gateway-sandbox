# Operacao

## Subir o ambiente

```bash
cp .env.example .env            # preencher; STRIPE_API_KEY so se PAYMENT_PROVIDER=stripe
docker compose up -d --build    # postgres + kafka + app
docker compose up -d --build app   # depois de mudar codigo
docker compose down -v          # zera o banco (apaga o volume)
```

Sem container da app (debug/hot reload): suba so `postgres` e `kafka` e use `./mvnw spring-boot:run` (exporte o `.env` antes — ver [[environment|Ambiente]]).

Logs: `docker logs payment-gateway-app`. Banco: `docker exec -it payment-gateway-postgres psql -U postgres -d payment_gateway`.

## Testar

```bash
./mvnw test                                   # tudo (integracao exige Docker)
./mvnw test -Dtest='PaymentIdempotencyRaceTest,PaymentServiceRefundTest'
./mvnw clean test                             # se target/ corrompeu
```

Fluxo manual pelo Swagger/curl: ver `NOTAS.md` (criar, replay, history, cancel, refund). Com o fake o pagamento ja nasce `CAPTURED`; com `STRIPE_TEST_PAYMENT_METHOD=pm_card_visa` tambem. Sem ele, forcar `CAPTURED` no banco para testar estorno.

## Trocar de provider

Editar `PAYMENT_PROVIDER` no `.env` e recriar a app (`docker compose up -d --build app`). `fake` para carga e dev sem rede; `stripe` para integracao real no sandbox.

## Banco

- Migrations rodam sozinhas no start (Flyway). Erro de validacao do Hibernate no start = entidade e schema divergiram.
- Pagamentos antigos persistem no volume entre `down`/`up`; `down -v` para zerar.

## Git e entrega

- Branch de integracao: `hml`; `main` e a principal. Cada entrega em branch propria a partir da `hml` atualizada (`feat/...`, `fix/...`, `docs/...`).
- O agente faz commit e push da branch; **quem abre o PR e faz o merge e o dono do projeto** (o `gh` nao esta instalado). Depois do merge o dono da o pull e avisa.
- Commits em portugues, sem acento, prefixo convencional (`feat:`, `fix:`, `docs:`), **sem `Co-Authored-By`** (ADR-008).
- Nao usar `--force` em branch compartilhada; reescrever so branch propria, recem-criada e sem PR, e avisar.

## Deploy (alvo, v5)

AWS free tier: RDS Postgres + EC2 com a app e o Kafka em container (MSK fora por custo — decisao consciente a documentar). Na instancia de deploy `payment.provider=stripe`; o profile `loadtest` so nas execucoes do k6. Ainda nao implementado.

## Contexto de IA (versionado)

`AGENTS.md` e `knowledgebase/` ficam no Git; nao colocar no `.gitignore`. Ao finalizar feature/fix/chore, atualize a knowledge base e inclua no commit/PR.
