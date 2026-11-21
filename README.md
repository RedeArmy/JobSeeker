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

## Stack de desarrollo local

```bash
bash scripts/dev-up.sh          # Postgres 17, Redis, Mailpit, Prometheus, Alertmanager y Grafana
bash scripts/dev-up.sh --app    # además, la aplicación en un contenedor
bash scripts/dev-up.sh --ai     # además, Ollama y el modelo de embeddings (imagen y modelo pesados)
bash scripts/dev-down.sh        # detiene el stack y conserva los datos (--volumes los borra)
```

La primera vez genera `.env` con contraseñas aleatorias y puertos libres (no se versiona). Todo se
publica solo en `127.0.0.1`. Con la aplicación en el anfitrión, carga ese `.env` y ejecútala:
`set -a; . ./.env; set +a`, y luego `java --enable-native-access=ALL-UNNAMED -jar app/target/app-*-SNAPSHOT.jar`.
El esquema lo crea Flyway al arrancar. `scripts/smoke-test-jar.sh` comprueba el jar con una base
desechable.
