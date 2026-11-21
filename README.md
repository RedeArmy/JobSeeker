# JobSeeker

Sistema ATS + CRM con un motor de compatibilidad CV–vacante.

Monolito modular construido con Java 25 y Spring Boot 4.1 (ADR-0014, aprobado), frontend
renderizado en el servidor (Thymeleaf + HTMX), PostgreSQL 17 con pgvector, Redis y una capa
de IA híbrida (Ollama local + API de Claude de uso selectivo).

> Esqueleto del proyecto — la documentación, el detalle de los módulos y las instrucciones
> de instalación se irán añadiendo a medida que se construya el sistema.

## Módulos

- `auth-module`
- `cv-module`
- `job-module`
- `matching-module`
- `ats-module`
- `tracker-module`
- `common`
- `app` — el único desplegable (los módulos de negocio son bibliotecas)

Consulta `docs/` para la documentación de arquitectura, producto y dominio.
