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

## Cómo construir

Requisitos: JDK 25 (Temurin) y Maven 3.9.

```bash
mvn verify                  # formato, análisis estático, pruebas, reglas de arquitectura y cobertura
mvn spotless:apply          # corrige el formato si `verify` lo rechaza
java --enable-native-access=ALL-UNNAMED -jar app/target/app-0.1.0-SNAPSHOT.jar
```

`mvn verify` es el mismo comando que ejecuta el CI. Las pruebas de integración con Testcontainers
necesitan Docker, y arrancan un Postgres 17 con pgvector (la misma versión mayor que Supabase ofrece).

Para ejecutar el jar hace falta una base de datos. Con el perfil `dev` los datos de conexión salen de
`DB_DEV_*` (ver `.env.example`); el esquema lo crea Flyway al arrancar. `scripts/smoke-test-jar.sh`
hace todo esto de forma desechable.
