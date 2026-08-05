# JobSeeker

ATS + CRM system with a CV-job compatibility matching engine.

Modular monolith built with Java 21 and Spring Boot 3.4, server-side
rendered frontend (Thymeleaf + HTMX), PostgreSQL 16 with pgvector,
Redis, and a hybrid AI layer (local Ollama + selective Claude API).

> Project skeleton — documentation, module details, and setup
> instructions will be added as the system is built out.

## Modules

- `auth-module`
- `cv-module`
- `job-module`
- `matching-module`
- `ats-module`
- `tracker-module`
- `common`

See `docs/` for architecture, product, and domain documentation.
