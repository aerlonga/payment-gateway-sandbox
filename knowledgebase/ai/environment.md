# Ambiente

## Docker

| Item | Valor |
|------|-------|
| App HTTP | http://localhost:8080 |
| Swagger UI | http://localhost:8080/swagger-ui.html |
| OpenAPI JSON | http://localhost:8080/v3/api-docs |
| Postgres | localhost:`DB_PORT` (5432), banco `payment_gateway`, volume `postgres_data` |
| Kafka | `localhost:9092` (host), `kafka:29092` (entre containers), KRaft, sem autenticacao (PLAINTEXT, so sandbox) |

## Arquivos

- App env: `.env` na raiz (a partir de `.env.example`; esta no `.gitignore`)
- Compose: `docker-compose.yml`; **override local** (nao versionado): `docker-compose.override.yml`
- Config da app: `src/main/resources/application.yml`, `application-loadtest.yml`

## Variaveis

| Variavel | Uso |
|----------|-----|
| `DB_HOST` / `DB_PORT` / `DB_NAME` / `DB_USER` / `DB_PASSWORD` | Postgres (defaults locais no `application.yml`; o compose passa `DB_HOST=postgres`) |
| `KAFKA_BOOTSTRAP_SERVERS` | `localhost:9092` fora do Docker; `kafka:29092` no compose |
| `PAYMENT_PROVIDER` | `fake` (default) ou `stripe` |
| `STRIPE_API_KEY` | chave **secreta** do sandbox (`sk_test_...`); obrigatoria com `PAYMENT_PROVIDER=stripe` (a app nao sobe sem ela). Nunca a publicavel. |
| `STRIPE_TEST_PAYMENT_METHOD` | opcional, ex.: `pm_card_visa`: confirma e captura na criacao; vazio deixa o pagamento `PENDING` |
| `FAKE_LATENCY_MS_MIN` / `FAKE_LATENCY_MS_MAX` / `FAKE_FAILURE_RATE` | fake provider (defaults 0/0/0); `application-loadtest.yml` usa 200/400/0.02; repassadas pelo compose |
| `payment.stripe.connect-timeout-ms` / `read-timeout-ms` / `max-network-retries` | 5000 / 10000 / 2 (so em `application.yml`) |
| `SPRING_PROFILES_ACTIVE=loadtest` | perfil do teste de carga (provider fake com latencia); repassado pelo compose |

Fora do Docker, o Spring **nao le `.env` sozinho**: `set -a; source .env; set +a` antes de `./mvnw spring-boot:run`. O compose le o `.env` automaticamente; variavel exportada no shell tem precedencia sobre ele (ex.: `PAYMENT_PROVIDER=fake docker compose up -d app`).

## Rede com inspecao TLS (so em algumas maquinas)

Em redes onde um firewall reassina o TLS (ex.: Fortinet), o container Java nao confia na CA e a chamada ao Stripe falha com `PKIX path building failed` (502 no create). **Nao** desligar a verificacao de certificado. Solucao local, sem alterar o repositorio:

1. Exportar a CA da cadeia (`openssl s_client -showcerts`) para `certs/corp-ca.crt`.
2. Gerar `certs/truststore.jks` = `cacerts` do JRE da imagem + a CA (`keytool -importcert`).
3. `docker-compose.override.yml` monta o JKS e seta `JAVA_TOOL_OPTIONS=-Djavax.net.ssl.trustStore=...`.

`certs/` e `docker-compose.override.yml` ficam em `.git/info/exclude` (local). Fora dessa rede nada disso e necessario.

## Testes de integracao

Precisam de Docker (Testcontainers sobe Postgres, e Kafka no `contextLoads`). Nao precisam do `.env`.

## Cuidados

- `target/` pode ficar corrompido ao trocar de branch (erro `ClassFormatError` / "Unresolved compilation problem" em classe Lombok). Corrija com `./mvnw clean test`.
- Os pagamentos de teste no Stripe ficam no painel do sandbox; sao descartaveis.
