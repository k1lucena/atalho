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

## Licença

[MIT](LICENSE)
