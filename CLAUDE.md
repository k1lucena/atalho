# Atalho

Encurtador de links. O redirect lê o link do Redis e publica o clique no RabbitMQ; um worker
grava os cliques no Postgres. Projeto de portfólio com foco em back-end Java.

## Stack
- Java 21, Spring Boot 4, Maven multi-módulo (`backend/`)
- Postgres 17 (Flyway), Redis 7, RabbitMQ 4
- Next.js + TypeScript (`frontend/`)
- Prometheus + Grafana (`infra/`), k6 (`load-tests/`), Caddy como gateway

## Módulos
- `backend/atalho-common`: contratos compartilhados (`ClickEvent`, nomes de filas). Sem dependência de Spring.
- `backend/atalho-api`: auth, links, QR, stats, redirect, rate limit. **Único módulo que roda Flyway.**
- `backend/atalho-worker`: consome `clicks.q`, enriquece (país/dispositivo), grava em lote.
- `frontend/`: UI. Fala com a API só por `/api/*` (mesmo domínio, via gateway).

## Comandos
- Subir tudo: `docker compose up -d --build` → http://localhost (Grafana: http://localhost:3001)
- Testes do back: `cd backend && ./mvnw verify` (precisa do Docker rodando, por causa do Testcontainers)
- Um teste: `./mvnw -pl atalho-api verify -Dit.test=RedirectIT`
- Front: `cd frontend && npm run lint && npm run typecheck && npm run build`
- Carga: `docker compose --profile load run --rm k6 run /scripts/redirect-hot.js`
- Baixar a base de GeoIP: `./scripts/download-geoip.sh` (o .mmdb não é commitado)

## Regras do projeto
- Código, nomes, commits e comentários em **inglês**. README e docs em **português**.
- Commits no padrão Conventional Commits (`feat:`, `fix:`, `test:`, `chore:`…).
- **Caminho do redirect (`GET /{code}`) não faz escrita síncrona no banco** nem chamada de rede
  além do Redis (o Postgres só entra no cache miss). Publicar o clique é assíncrono e nunca falha o request.
- Migrations: nunca edite um `V*__*.sql` que já está na main. Toda mudança de schema é uma nova migration.
- **Nunca guarde o IP do visitante** (nem em log). Só o país derivado dele.
- Toda mudança em link (PATCH/DELETE) invalida `link:{code}` no Redis.
- O worker é idempotente: `click_events.id` = `eventId` e insert com `ON CONFLICT DO NOTHING`.
- Erros da API sempre em ProblemDetail (`application/problem+json`).
- Todo endpoint novo ganha anotações OpenAPI (summary, respostas de erro) e aparece no Swagger.
- Teste de integração usa **Testcontainers** (`@ServiceConnection`), nunca H2 nem mocks de Redis/Rabbit.
  Nomes: `*Test` (unit, Surefire) e `*IT` (integração, Failsafe).
- Configuração por variável de ambiente; segredos só em `.env` (que não vai para o git). Mantenha o `.env.example` atualizado.
- O Actuator não é exposto pelo gateway.
- Código organizado por feature (`link/`, `redirect/`, `stats/`…), não por camada.

## Métricas que não podem sumir (usadas nos dashboards e no README)
`atalho_redirect_seconds`, `atalho_redirect_cache_total{result}`, `atalho_click_publish_failures_total`,
`atalho_ratelimit_rejected_total{scope}`, `atalho_worker_batch_seconds`.
Renomear exige atualizar `infra/grafana/dashboards/atalho.json`.

## Antes de dizer que terminou
1. `./mvnw verify` passa.
2. Se mexeu no front: lint + typecheck + build passam.
3. Se mexeu em schema, filas ou compose: `docker compose up` do zero funciona.
4. Se mexeu no redirect: rode `redirect-hot.js` e compare o p95 com `load-tests/results/README.md`.
