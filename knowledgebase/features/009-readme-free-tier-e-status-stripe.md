# README: free tier AWS e status do Stripe

## Status

- Tipo: chore
- Status: implementado
- Ultima atualizacao: 2026-10-26

## Objetivo

Corrigir dois pontos desatualizados do README: a premissa de "free tier de 12 meses com t2/t3.micro" e a linha de status do `StripePaymentProvider`.

## Contexto

Contas AWS criadas a partir de 15/07/2025 usam um plano gratuito com creditos (US$ 100, mais ate US$ 100 por atividades), valido por 6 meses ou ate acabarem os creditos. Alem disso, uma t3.micro (1 GB) nao comporta Spring Boot e Kafka juntos. O `StripePaymentProvider` ja foi validado contra o sandbox real (feature 004), mas a linha 13 do README dizia o contrario.

## Comportamento

So documentacao. Nenhum codigo alterado.

## Arquivos E Pontos De Entrada

- `README.md` (intro, diagrama, secao 7 de carga, secao 8 de deploy, roadmap v5, status secao 13)

## Variaveis De Ambiente

Nenhuma.

## Validacao

`git diff` do README; `grep -n 'free tier' README.md` nao deve achar a premissa antiga.

## Pendencias

- Conferir o tipo da conta no console de Billing e criar alerta no AWS Budgets antes do deploy.
- Registrar a escolha de infraestrutura e trade-offs em ADR quando o deploy entrar em desenvolvimento.
- Webhook do Stripe ainda nao validado (depende da v2).
