# Plano — Atalho (encurtador de links)

## Contexto

Projeto de portfólio para vagas de **back-end Java**. A ideia é um encurtador em que o redirect é rápido (link vem do Redis) e o clique é registrado de forma assíncrona (RabbitMQ → worker → Postgres). Prazo de 5 semanas × 8–10 h (≈ 45 h no total).

Respostas que guiam o plano: login **e-mail + senha com JWT**; **código e commits em inglês, README em português**; **Java 21 + Maven**; deploy **ainda em aberto** (fica como decisão nº 1).

Situação atual: `C:\develop\Projetos\Atalho` está vazia e fica **dentro do repositório git `C:\develop`** (o mesmo do MoFit). O Atalho precisa de um repositório próprio (ver Riscos).

---

## 1. Arquitetura

```
                       ┌─────────────────────────── docker compose ───────────────────────────┐
 Navegador ──HTTPS──▶  │  gateway (Caddy)                                                     │
                       │   ├─ /, /login, /dashboard/*, /_next/*  ──▶ web (Next.js)            │
                       │   ├─ /api/*                             ──▶ api (Spring Boot)        │
                       │   └─ /{code}  (todo o resto)            ──▶ api  (redirect)          │
                       │                                                                      │
                       │  api ──GET/SET──▶ Redis  (cache de links + baldes do rate limit)     │
                       │  api ──SQL─────▶ Postgres (usuários, links)   [Flyway roda aqui]     │
                       │  api ──publish─▶ RabbitMQ  clicks.x → clicks.q  (DLX → clicks.dlq)   │
                       │                                   │                                  │
                       │  worker (Spring Boot) ◀─consume───┘ ──batch insert/upsert─▶ Postgres │
                       │                                                                      │
                       │  Prometheus ──scrape──▶ api, worker (/actuator/prometheus),          │
                       │                         RabbitMQ (:15692)                            │
                       │  Grafana ──▶ Prometheus  (dashboards provisionados por arquivo)      │
                       │  k6 (perfil "load") ──▶ gateway ; envia métricas → Prometheus        │
                       └──────────────────────────────────────────────────────────────────────┘
```

### Componentes

| Componente | Função | Tecnologia |
|---|---|---|
| **gateway** | Ponto de entrada único, HTTPS automático em produção, roteia por caminho, define `X-Forwarded-For` | Caddy 2 |
| **web** | Login/cadastro, lista e criação de links, QR, dashboard de gráficos | Next.js (App Router, TS), Tailwind, Recharts |
| **api** | Autenticação, CRUD de links, QR Code, estatísticas, **redirect**, rate limiting, publica eventos de clique | Spring Boot, Spring Security, Spring Data JPA, Spring AMQP, Lettuce, ZXing, springdoc |
| **worker** | Consome cliques em lote, descobre país e dispositivo, grava evento cru e agregado diário | Spring Boot, Spring AMQP, GeoIP2 reader, uap-java |
| **Postgres** | Fonte da verdade | Postgres 17 |
| **Redis** | Cache de links (cache-aside), cache negativo, rate limit | Redis 7 |
| **RabbitMQ** | Fila de cliques que desacopla o redirect da escrita | RabbitMQ 4 + plugins management e prometheus |
| **Prometheus / Grafana** | Métricas e dashboards | Micrometer + Actuator |

Por que dois apps (api e worker): o consumidor escala sozinho e uma queda dele não afeta o redirect. Isso é uma boa conversa de entrevista. Os dois compartilham só um módulo pequeno, `atalho-common` (o record `ClickEvent`).

### Fluxo do redirect (caminho quente)

1. `GET /{code}` → **rate limit** por IP (Redis, token bucket). Se estourar, `429` com `Retry-After`.
2. Redis `GET link:{code}`:
   - **hit** → segue para o passo 3.
   - **miss** → consulta o Postgres. Se achou, faz `SET link:{code}` com TTL = `min(24h, expires_at − agora)`. Se não achou, faz `SET link:miss:{code}` com TTL de 60 s (cache negativo contra *cache penetration*).
3. Expirado → `410 Gone`; inexistente → `404`; ok → `302 Found` com `Location`.
4. Publica o `ClickEvent` (eventId UUID, code, linkId, timestamp, IP, User-Agent, Referer) **depois de montar a resposta, sem bloquear o request** (executor de virtual threads). Uma falha ao publicar vira métrica e log; não vira erro para o usuário.
5. Nenhuma escrita síncrona no banco durante o redirect.

### Fluxo do worker

`clicks.q` (prefetch ~250, listener em lote) → descobre o país pelo IP (arquivo mmdb local) e o tipo de dispositivo pelo User-Agent → **descarta o IP** → `INSERT … ON CONFLICT (id) DO NOTHING` em `click_events` (idempotente, porque a entrega é *at-least-once*) → upsert em `click_daily_stats` → `ack`. Erro: 3 tentativas com backoff e depois DLQ.

### Contratos da API (documentados com OpenAPI/springdoc)

- `POST /api/auth/register | login | refresh | logout`, `GET /api/me`
- `POST /api/links` `{url, alias?, expiresAt?}` · `GET /api/links?page=` · `GET|PATCH|DELETE /api/links/{id}`
- `GET /api/links/{id}/qr?format=png|svg&size=` (ZXing, com `ETag`)
- `GET /api/links/{id}/stats?from=&to=` → `{total, byDay[], byCountry[], byDevice[]}`
- `GET /{code}` → 302 / 404 / 410 / 429
- Erros sempre em **ProblemDetail (RFC 9457)**. O Actuator fica fora do gateway, acessível só pela rede interna.

### Autenticação

Access token JWT curto (15 min), guardado em memória no front. Refresh token opaco (7 dias) num cookie `httpOnly; Secure; SameSite=Lax`, salvo **como hash** no banco e trocado a cada uso. Senha com BCrypt. Como front e API ficam no mesmo domínio (via gateway), não há problema de CORS nem de cookie entre sites.

---

## 2. Modelo de dados

### Postgres (migrations Flyway)

```sql
users           (id BIGINT IDENTITY PK, email CITEXT UNIQUE NOT NULL, password_hash TEXT NOT NULL,
                 role VARCHAR(16) NOT NULL DEFAULT 'USER', created_at TIMESTAMPTZ NOT NULL)

refresh_tokens  (id UUID PK, user_id BIGINT FK→users ON DELETE CASCADE, token_hash TEXT UNIQUE NOT NULL,
                 expires_at TIMESTAMPTZ NOT NULL, revoked_at TIMESTAMPTZ NULL, created_at TIMESTAMPTZ NOT NULL)

links           (id BIGINT IDENTITY PK, user_id BIGINT FK→users, code VARCHAR(32) NOT NULL,
                 target_url TEXT NOT NULL, is_custom BOOLEAN NOT NULL, expires_at TIMESTAMPTZ NULL,
                 click_count BIGINT NOT NULL DEFAULT 0,          -- denormalizado, mantido pelo worker
                 created_at TIMESTAMPTZ NOT NULL, deleted_at TIMESTAMPTZ NULL)
  UNIQUE (code)                                    -- ou UNIQUE (lower(code)): decisão 5
  INDEX  (user_id, created_at DESC) WHERE deleted_at IS NULL

click_events    (id UUID PK,                       -- eventId gerado na api → idempotência
                 link_id BIGINT FK→links, occurred_at TIMESTAMPTZ NOT NULL,
                 country_code CHAR(2) NULL, device_type VARCHAR(16) NOT NULL,  -- desktop|mobile|tablet|bot|other
                 browser VARCHAR(32) NULL, os VARCHAR(32) NULL, referrer_host VARCHAR(255) NULL)
  INDEX (link_id, occurred_at)                     -- particionar por mês fica como melhoria futura

click_daily_stats (link_id BIGINT FK→links, day DATE, country_code CHAR(2), device_type VARCHAR(16),
                   clicks BIGINT NOT NULL,
                   PRIMARY KEY (link_id, day, country_code, device_type))   -- 'ZZ' para país desconhecido
```

O dashboard lê **só** `click_daily_stats`: um `GROUP BY` por dia, outro por país e outro por dispositivo, todos no mesmo índice. `click_events` guarda o histórico para reprocessar ou fazer outras análises depois.

**Não se guarda IP** em nenhuma tabela (LGPD). Só o país derivado dele.

### Redis

| Chave | Valor | TTL |
|---|---|---|
| `link:{code}` | JSON `{id, url, expiresAt}` | `min(24h, expiresAt − now)`; apagada no PATCH/DELETE |
| `link:miss:{code}` | `1` | 60 s; apagada ao criar um link com esse código |
| `rl:{scope}:{ip\|userId}` | estado do token bucket | expira sozinho |

### RabbitMQ

`clicks.x` (direct) → `clicks.q` (durável, `x-dead-letter-exchange=clicks.dlx`) · `clicks.dlx` → `clicks.dlq`. Tudo declarado no código (`Declarables`) na api e no worker.

### Migrations previstas

`V1__create_users.sql`, `V2__create_refresh_tokens.sql`, `V3__create_links.sql`, `V4__create_click_tables.sql`. **Só a api roda Flyway**; no worker, `spring.flyway.enabled=false`. Alembic não se aplica, porque não há Python no projeto.

---

## 3. Estrutura de pastas

```
atalho/
├── backend/
│   ├── pom.xml                         # parent multi-módulo (Java 21, versões, plugins)
│   ├── atalho-common/                  # ClickEvent, constantes de fila
│   ├── atalho-api/
│   │   ├── Dockerfile
│   │   └── src/
│   │       ├── main/java/dev/atalho/api/
│   │       │   ├── auth/               # controller, JwtService, SecurityConfig, refresh tokens
│   │       │   ├── user/
│   │       │   ├── link/               # CRUD, CodeGenerator, AliasValidator
│   │       │   ├── redirect/           # RedirectController, LinkCache
│   │       │   ├── click/              # ClickEventPublisher
│   │       │   ├── qrcode/
│   │       │   ├── stats/
│   │       │   ├── ratelimit/
│   │       │   └── common/             # erros (ProblemDetail), config, OpenAPI
│   │       ├── main/resources/db/migration/   # V1__…sql
│   │       └── test/java/…             # unit + *IT (Testcontainers)
│   └── atalho-worker/
│       ├── Dockerfile
│       └── src/main/java/dev/atalho/worker/
│           ├── consumer/               # listener em lote, retry, DLQ
│           ├── enrichment/             # GeoIpResolver, DeviceClassifier
│           └── persistence/            # batch insert / upsert (JdbcTemplate)
├── frontend/                           # Next.js (app/, components/, lib/api.ts), Dockerfile
├── infra/
│   ├── caddy/Caddyfile
│   ├── prometheus/prometheus.yml
│   ├── grafana/provisioning/{datasources,dashboards}/  + dashboards/atalho.json
│   ├── rabbitmq/{rabbitmq.conf,enabled_plugins}
│   └── geoip/README.md                 # o .mmdb é baixado por script e fica no .gitignore
├── load-tests/
│   ├── scripts/{redirect-hot,redirect-cold,create-links,rate-limit,mixed}.js
│   ├── lib/                            # setup comum, geração de dados
│   └── results/README.md               # tabelas com números reais + data/hardware
├── scripts/                            # download-geoip.{sh,ps1}, seed-demo
├── docs/
│   ├── plano.md
│   ├── adr/0001-…md                    # uma ADR curta por decisão grande
│   └── img/{demo.gif,grafana.png,architecture.png}
├── .github/workflows/{backend.yml,frontend.yml,images.yml,deploy.yml}
├── docker-compose.yml                  # sobe TUDO com `docker compose up`
├── docker-compose.prod.yml             # override: domínio, limites de memória, sem portas expostas
├── .env.example
├── CLAUDE.md
└── README.md
```

---

## 4. Backlog por semana

As estimativas estão entre parênteses. **DoD** é o critério de pronto. Toda tarefa de back-end só fica pronta com teste passando no CI.

### Semana 1: fundação e autenticação (≈ 9 h)

| # | Tarefa | DoD |
|---|---|---|
| 1.1 | Criar o repo `atalho` no GitHub (fora do repo `C:\develop`), `.gitignore`, LICENSE MIT, README esqueleto (0,5 h) | Push inicial; README com título e "em construção" |
| 1.2 | Maven multi-módulo: parent + common + api + worker, Spring Boot, Java 21 (1 h) | `mvn verify` passa localmente com os dois apps subindo vazios |
| 1.3 | `docker-compose.yml` com Postgres, Redis e RabbitMQ (com healthchecks) e `.env.example` (1 h) | `docker compose up` deixa os 3 *healthy*; management do Rabbit acessível |
| 1.4 | Flyway V1–V2, entidades `User` e `RefreshToken` (1 h) | Teste `MigrationIT` (Testcontainers Postgres) aplica as migrations do zero |
| 1.5 | Register/login/refresh/logout com JWT, BCrypt e rotação de refresh (3 h) | ITs: login ok → 200; senha errada → 401; rota protegida sem token → 401; refresh reutilizado → 401 |
| 1.6 | Erros padronizados (ProblemDetail) + springdoc (1 h) | `/swagger-ui` lista auth com schemas e exemplos; erro de validação retorna `application/problem+json` |
| 1.7 | GitHub Actions `backend.yml`: `mvn verify` com Testcontainers e cache do Maven (1 h) | Badge verde no README; PR com teste quebrado fica vermelho |

### Semana 2: links, redirect com cache e QR (≈ 10 h)

| # | Tarefa | DoD |
|---|---|---|
| 2.1 | `CodeGenerator`: base62 aleatório de 7 caracteres com `SecureRandom` e retry em colisão (1 h) | Unit tests: tamanho, alfabeto, retry quando o repositório informa colisão |
| 2.2 | `POST /api/links`: valida URL (só http/https, ≤ 2 KB, não aponta para o próprio domínio), alias (regex `[A-Za-z0-9_-]{3,32}`, lista de palavras reservadas), `expiresAt` no futuro (2 h) | ITs: 201; URL inválida → 400; alias em uso → 409; alias reservado (`api`, `login`…) → 400 |
| 2.3 | `GET/PATCH/DELETE /api/links` paginado; o dono é o único que vê (1,5 h) | IT: usuário B recebe **404** ao acessar link do usuário A |
| 2.4 | `GET /{code}` com cache-aside, cache negativo, TTL limitado pela validade, 302/404/410; invalidação no PATCH/DELETE (2,5 h) | ITs (Postgres + Redis): 2º acesso não chama o repositório (spy/métrica); link vencido → 410; depois do PATCH o redirect vai para a URL nova |
| 2.5 | QR Code com ZXing (PNG/SVG) (1 h) | Teste decodifica o PNG gerado e obtém exatamente a URL curta |
| 2.6 | Métricas customizadas: `atalho_redirect_cache_total{result=hit\|miss\|negative}` e timer `atalho_redirect` com percentis (1 h) | `/actuator/prometheus` mostra as séries depois de 1 redirect |
| 2.7 | Dockerfile multi-stage (layered jar, usuário não-root, `MaxRAMPercentage`) para api e worker (1 h) | `docker compose up` sobe a api; `curl -I localhost/{code}` → 302 |

### Semana 3: fila de cliques, worker e estatísticas (≈ 10 h)

| # | Tarefa | DoD |
|---|---|---|
| 3.1 | Topologia Rabbit como código: exchange, fila, DLX e DLQ (1 h) | As 2 filas aparecem no management depois do boot |
| 3.2 | `ClickEventPublisher` assíncrono, chamado no redirect (1,5 h) | IT (Rabbit Testcontainer): o redirect gera 1 mensagem com os campos certos; com o Rabbit parado, o redirect **continua** retornando 302 e a métrica de falha sobe |
| 3.3 | Worker: listener em lote, idempotência, retry e DLQ (2,5 h) | ITs: mensagem duplicada é contada 1 vez; mensagem malformada vai para a DLQ depois de 3 tentativas |
| 3.4 | Enriquecimento: `GeoIpResolver` (mmdb) e `DeviceClassifier` (uap-java); script `download-geoip` (2 h) | Unit tests com IPs e User-Agents fixos (desktop, iPhone, Googlebot); IP não aparece em nenhuma coluna nem log |
| 3.5 | Flyway V4 + batch insert/upsert em `click_daily_stats` e `links.click_count` (1,5 h) | IT: 100 eventos publicados → agregados exatos por dia, país e dispositivo |
| 3.6 | `GET /api/links/{id}/stats?from&to` (1,5 h) | IT: dias sem clique voltam com 0; intervalo > 90 dias → 400; link de outro usuário → 404 |

### Semana 4: rate limiting, front e observabilidade (≈ 10 h)

| # | Tarefa | DoD |
|---|---|---|
| 4.1 | Rate limit em Redis (Bucket4j + Lettuce): redirect por IP, login por IP, criação de links por usuário; headers `RateLimit-*` e `Retry-After`; IP real só confiando no gateway (2,5 h) | ITs: a requisição N+1 recebe 429; `X-Forwarded-For` forjado direto na api é ignorado; os limites vêm de config |
| 4.2 | Next.js: login, cadastro, botão "Entrar como demo", lista e criação de links (alias, validade), QR com botão de download (3 h) | Fluxo completo funcionando via `docker compose up` em `http://localhost` |
| 4.3 | Next.js: dashboard com cliques por dia (linha), país (barra) e dispositivo (rosca), com filtro de período (2 h) | Gráficos batem com o endpoint de stats; estado vazio tratado |
| 4.4 | Prometheus + Grafana provisionados: req/s, p95/p99 do redirect, taxa de cache hit, profundidade da fila e taxa de consumo, 429/s, JVM/heap (2 h) | Depois de `docker compose up` (sem nenhum clique manual), o dashboard "Atalho" aparece preenchido assim que há tráfego |
| 4.5 | Caddy + compose final com todos os serviços e `depends_on: condition: service_healthy` (0,5 h) | Clone limpo + `cp .env.example .env` + `docker compose up` → tudo funciona |

### Semana 5: carga, deploy e README (≈ 10 h)

| # | Tarefa | DoD |
|---|---|---|
| 5.1 | Scripts k6: `redirect-hot`, `redirect-cold` (flag `ATALHO_CACHE_ENABLED=false`), `create-links`, `rate-limit`, `mixed`; thresholds (p95, taxa de erro); serviço `k6` no compose (perfil `load`) com saída para o Prometheus (2 h) | `docker compose --profile load run k6 run /scripts/redirect-hot.js` roda e aparece no Grafana |
| 5.2 | Rodar os benchmarks e registrar os resultados (2 h) | `load-tests/results/README.md` com hardware, versão, VUs, duração, **req/s, p50/p95/p99, % de erro**, tabela **com cache × sem cache** e print do Grafana |
| 5.3 | Seed demo: usuário demo, ~10 links e ~30 dias de cliques sintéticos; reset diário (1 h) | Login demo abre um dashboard cheio; o reset roda sem intervenção manual |
| 5.4 | Deploy público: `images.yml` (build e push para o GHCR), `deploy.yml` (SSH + `compose pull && up -d`), domínio + HTTPS, Grafana só leitura (2,5 h) | URL pública com HTTPS; demo funciona; `/actuator` não é acessível de fora; CI faz deploy no merge para main |
| 5.5 | README: GIF, diagrama, como rodar, resultados de carga, link do Swagger e da demo, seção **"Decisões e trade-offs"** (1,5 h) | Alguém sem contexto consegue rodar o projeto só com o README |
| 5.6 | Buffer e polimento (smoke k6 de 30 s no CI é opcional) (1 h) | — |

**Plano de corte** (se atrasar, corte nesta ordem): smoke k6 no CI → PATCH de link → SVG do QR → filtro de período no dashboard → rotação de refresh token (fica só access token mais longo). **Nunca corte:** testes, CI, compose, números de carga, README.

---

## 5. Decisões a tomar e riscos

### Decisões (com a minha recomendação)

| # | Decisão | Recomendação | Quando decidir |
|---|---|---|---|
| 1 | **Onde fazer o deploy** | VPS com o mesmo compose (Hetzner CAX11/CX22, ~4–5 €/mês, 4 GB de RAM; Oracle Free ARM é grátis, mas a capacidade é instável). Um PaaS com 8 serviços sai caro ou esbarra nos limites do free tier | Até o fim da semana 3 |
| 2 | Domínio | Um domínio curto e barato (`.xyz`/`.dev`) mostra o produto melhor. Para gastar zero, `sslip.io`/DuckDNS | Semana 4 |
| 3 | Versão do Spring Boot | **4.0.x** (a linha 3.x saiu do suporte OSS; confira em spring.io/projects/spring-boot#support). Antes, verifique se springdoc 3.x, Bucket4j e Testcontainers 2.x são compatíveis | Semana 1, tarefa 1.2 |
| 4 | 301 ou 302 | **302**: o navegador guarda o 301 em cache e os cliques seguintes não chegam ao servidor | Já recomendado |
| 5 | Diferenciar maiúsculas no código | Códigos gerados diferenciam maiúsculas (base62). Aliases passam para minúsculas e o índice é `UNIQUE(lower(code))`, para evitar `Promo` e `promo` apontando para lugares diferentes | Semana 2 |
| 6 | Garantia de entrega do clique | Publicação assíncrona com *publisher confirms* e métrica de falha. Aceita-se perder cliques se a api cair. O Outbox fica como evolução documentada nos trade-offs | Semana 3 |
| 7 | Algoritmo e valores do rate limit | Token bucket (Bucket4j). Redirect: 120/min por IP; login: 10/min por IP; criação: 30/min por usuário. Em benchmark, os limites sobem por config | Semana 4 |
| 8 | Fonte de geolocalização | **DB-IP Lite** (CC BY 4.0, sem cadastro, formato mmdb). O GeoLite2 da MaxMind exige conta e license key e não pode ser commitado | Semana 3 |
| 9 | Fuso do "clique por dia" | Agregar por **dia UTC** e deixar isso escrito na UI. Agregar no fuso do usuário exigiria ler `click_events`, o que é mais lento | Semana 3 |
| 10 | Grafana público | Acesso anônimo como *Viewer* só no dashboard "Atalho". É ótimo para quem avalia, e o admin continua com senha | Semana 5 |
| 11 | Cadastro aberto em produção | Fechado ou com captcha e cota. Só o usuário demo fica livre (ver risco de abuso) | Semana 5 |

### Riscos

| Risco | Impacto | Mitigação |
|---|---|---|
| **Abuso do encurtador público** (phishing/malware) | Domínio marcado pelo Safe Browsing e VPS suspensa | Cadastro fechado ou limitado; links do demo expiram em 24 h e têm cota; lista de domínios bloqueados; reset diário do demo; (opcional) checagem na Google Safe Browsing API |
| **Números de carga pouco confiáveis** (k6 e app na mesma máquina, Docker Desktop/WSL2 no Windows) | Números difíceis de defender em entrevista | Documentar hardware e condições; aquecer o sistema antes de medir; limitar CPU dos containers; reportar p95/p99, não só req/s; se possível, medir na VPS a partir de outra máquina |
| Rate limit derrubando o próprio teste de carga (k6 sai de 1 IP) | Benchmark mostra só 429 | Perfil de benchmark com limites altos; cenário `rate-limit.js` separado que prova o 429 |
| Memória da VPS (2 JVMs + Postgres + Redis + Rabbit + Next + Prometheus + Grafana) | OOM em produção | `-XX:MaxRAMPercentage`, `mem_limit` no compose de produção, retenção curta no Prometheus, Next em modo `standalone`. Meta: ≤ 3 GB |
| Repo dentro de `C:\develop` (que já é um repo git) | Commits do Atalho indo para o repo errado | Mover para fora ou criar um repo próprio e pôr `Projetos/Atalho/` no `.gitignore` do repo pai |
| Front consumindo tempo demais | Faltar tempo para carga, deploy e README | Limitar o front a ~5 h no total; usar componentes prontos (shadcn/ui) |
| Testcontainers no Windows | Testes de integração não rodam localmente | Docker Desktop com WSL2; o CI no Ubuntu é a referência |
| Cache inconsistente depois de editar ou expirar | Redirect para a URL antiga | TTL ≤ validade; `DEL` na escrita; IT dedicado (tarefa 2.4) |
| Prazo apertado (≈ 45 h) | Entregar incompleto | Plano de corte acima; buffer na semana 5 |

Temas para a seção **"Decisões e trade-offs"** do README: 302 × 301; cache-aside + cache negativo; fila em vez de escrita síncrona (com o ganho medido); perda de cliques aceita × Outbox; agregado diário × consulta sobre eventos crus; não guardar IP; api e worker separados; código aleatório × sequencial (enumeração).

---

## 6. CLAUDE.md (para a raiz do repositório)

````markdown
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
````

---

## Verificação deste plano (depois de implementado)

- `git clone` + `cp .env.example .env` + `docker compose up` → app em `http://localhost`, Swagger em `/swagger-ui`, Grafana com o dashboard.
- `./mvnw verify` verde localmente e no GitHub Actions (unit + ITs com Testcontainers).
- Roteiro manual: criar link com alias e validade → abrir o link curto → em segundos o clique aparece no dashboard (país/dispositivo) → editar a validade para o passado → 410.
- k6 `redirect-hot` e `redirect-cold` produzem os números registrados em `load-tests/results/`; `rate-limit.js` mostra 429.
- URL pública com HTTPS e login demo funcionando.
