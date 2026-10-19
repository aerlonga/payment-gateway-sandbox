# Knowledge base de IA e AGENTS.md

## Status

- Tipo: chore
- Status: implementado
- Ultima atualizacao: 2026-10-19

## Objetivo

Dar a agentes de IA (e a quem retoma o projeto em outra maquina) contexto versionado e rastreavel: o que o sistema e, como esta organizado, por que cada decisao foi tomada, como rodar/testar e o que cada entrega mudou.

## Contexto

O projeto vinha guardando contexto em `README.md` (especificacao) e `NOTAS.md` (notas de estudo), sem registro de decisoes nem de entregas. A estrutura (`AGENTS.md` + `knowledgebase/ai` + `knowledgebase/features`) foi trazida de outro projeto da casa e adaptada a este sistema.

## Comportamento

- Agentes leem `AGENTS.md` e `knowledgebase/ai/*` antes de alterar codigo.
- Decisao arquitetural vai para `knowledgebase/ai/decisions.md` (ADR); toda entrega deixa um documento em `knowledgebase/features/`.
- `README.md` continua sendo a especificacao e a tabela de status (secao 13); `NOTAS.md` segue como notas de estudo.

## Arquivos E Pontos De Entrada

- `AGENTS.md`
- `knowledgebase/ai/{index,project-overview,architecture,decisions,patterns,operations,environment}.md`
- `knowledgebase/features/{index,_template,000..005}.md`

## Variaveis De Ambiente

Nenhuma.

## Validacao

- Arquivos presentes e **fora** do `.gitignore`.
- Fatos conferidos contra o codigo: endpoints, tabelas/migrations V1-V4, variaveis do `application.yml`, comandos de teste e compose.

## Pendencias

- Manter `decisions.md` e `features/` atualizados a cada entrega (regra no `AGENTS.md`).
- Historico das features 001-005 foi reconstruido a partir do Git e das sessoes de desenvolvimento.
