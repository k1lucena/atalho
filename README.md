# Atalho

Encurtador de links rápido: o redirect busca o link no cache (Redis) e o clique é registrado em segundo plano por uma fila (RabbitMQ).

> 🚧 **Em construção.** O plano completo (arquitetura, modelo de dados e backlog) está em [docs/plano.md](docs/plano.md).

## Stack

- **Back-end:** Java 21, Spring Boot 4, Maven multi-módulo
- **Dados:** PostgreSQL 17 (migrations com Flyway), Redis 7
- **Mensageria:** RabbitMQ 4
- **Front-end:** Next.js + TypeScript
- **Observabilidade:** Prometheus + Grafana
- **Teste de carga:** k6

## Como rodar (dev)

Pré-requisitos: Docker Desktop e JDK 21.

```bash
# 1. Infraestrutura (Postgres, Redis e RabbitMQ)
docker compose up -d
docker compose ps          # os 3 serviços devem aparecer como "healthy"

# 2. Back-end (testes unitários + integração com Testcontainers)
cd backend
./mvnw verify              # no Windows: .\mvnw.cmd verify
```

| Serviço | Endereço | Usuário / senha |
|---|---|---|
| Postgres | `localhost:5432`, banco `atalho` | `atalho` / `atalho` |
| Redis | `localhost:6379` | — |
| RabbitMQ (AMQP) | `localhost:5672` | `atalho` / `atalho` |
| RabbitMQ (painel) | http://localhost:15672 | `atalho` / `atalho` |

Os valores padrão servem só para desenvolvimento. Para trocá-los, copie `.env.example` para `.env`.

## Licença

[MIT](LICENSE)
