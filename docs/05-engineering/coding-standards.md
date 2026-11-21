# Coding Standards
## Sistema Inteligente de Análisis de Compatibilidad CV-Vacante con CRM de Postulaciones

| Campo | Valor |
|---|---|
| **Estado** | 🟡 En revisión |
| **Versión** | 1.0 |
| **Fecha** | Agosto 2026 |
| **Documentos relacionados** | System Design Document, Domain Model, Test Strategy |

---

## 1. Propósito del documento

Fijar convenciones concretas de código Java/Spring Boot que complementan — sin duplicar en detalle — las reglas arquitectónicas ya fijadas en `domain-model.md` y `system-design-document.md` (DDD, arquitectura hexagonal, vertical slice, regla de dependencia). Este documento se enfoca en lo que esos documentos no cubren: convenciones de nombrado, empaquetado y herramientas de estilo.

## 2. Regla de dependencia y estructura de carpetas

Cada módulo de negocio se organiza en tres capas — `domain/`, `application/`, `infrastructure/` — con una regla de dependencia de una sola vía: `domain` no depende de nada externo; `application` depende solo de `domain`; `infrastructure` depende de `application` y `domain`, nunca al revés (ver el mapa de contextos en `domain-model.md` §5 y la arquitectura de alto nivel en `system-design-document.md` §4). Toda violación de esa regla (por ejemplo, una anotación `@Entity` o `@Autowired` dentro de `domain/`) se trata como defecto bloqueante, verificable en `definition-of-done.md`.

## 3. Convención de paquetes

```
com.usac.jobseeker.<módulo>.<capa>[.<sub-capa>]
```

Ejemplo: `com.usac.jobseeker.matching.application.usecase.calculatecompatibilityscore`.

- `<módulo>` coincide exactamente con el nombre del módulo de negocio (`auth`, `cv`, `job`, `matching`, `ats`, `tracker`) o `shared` para el shared-kernel.
- `<capa>` es una de `domain`, `application`, `infrastructure`, sin variantes de nombrado.

## 4. Convención de nombrado por tipo de clase

| Sufijo | Capa | Ejemplo |
|---|---|---|
| `Command` | `application/usecase/<caso-de-uso>/` | `CalculateCompatibilityScoreCommand` |
| `UseCase` | `application/usecase/<caso-de-uso>/` | `CalculateCompatibilityScoreUseCase` |
| `Port` | `application/usecase/<caso-de-uso>/` (interfaz de puerto de salida) | `CompatibilityScorePort` |
| `Controller` | `infrastructure/adapter/in/web/` | `JobPostingController` |
| `JpaRepository` / `...RepositoryAdapter` | `infrastructure/adapter/out/persistence/` | `JobPostingRepositoryAdapter` |
| `...Client` / `...Adapter` | `infrastructure/adapter/out/ai/` | `OllamaEmbeddingClient` |
| Sin sufijo técnico | `domain/model/` | `CandidateProfile`, `CompatibilityScore` (nombres de dominio puros, sin "Entity", "DTO", etc.) |

Una entidad de dominio (`domain/model/`) nunca lleva anotaciones JPA; su contraparte persistente (si se requiere una clase separada) vive en `infrastructure/adapter/out/persistence/` con su propio nombre (p. ej. `CandidateProfileJpaEntity`), mapeada explícitamente — nunca se reutiliza la clase de dominio como entidad JPA.

## 5. Estándar de comentarios

Todo comentario explica el **por qué**, nunca el qué (el código se explica solo mediante nombrado); referencia el ID de regla de negocio cuando aplica (`BR-XXX-NN`); máximo 1-3 líneas; cero código comentado; cero TODO sin dueño y razón.

**Idioma (decisión del 14-nov-2026):** por tratarse de un proyecto de tesis de la Universidad de San Carlos de Guatemala, **todos los comentarios, el Javadoc y los comentarios de configuración (`pom.xml`, YAML, Dockerfile, SQL de migraciones, scripts, workflows de CI) se escriben en español**. Se mantienen en inglés, sin traducir: los **identificadores** (paquetes, clases, métodos, variables, tablas y columnas, rutas de API, claves de configuración), las palabras reservadas y la sintaxis, y los **nombres propios de herramientas y patrones** (p. ej. *outbox*, *rate limit*). Cuando un término técnico tenga un equivalente natural en español, se prefiere este y el original se indica entre paréntesis y en cursiva la primera vez que aparece en un archivo (p. ej. «bandeja de salida (*outbox*)»). Los mensajes de commit, los *pull requests* y todo lo relativo a Git van en **inglés** (`git-workflow.md` §3). Los **mensajes de log** técnicos se escriben en español; los códigos estables (`code` del sobre de error de la API, nombres de métricas y claves de campos estructurados del log) permanecen en inglés y, en el caso de los códigos de error, en MAYÚSCULAS, por ser parte del contrato (`api-contract-overview.md` §6). El texto visible al usuario final se localiza en español (decisión del 14-nov-2026). Nunca se registran datos personales en los mensajes (BR-DATA-01).

## 6. Checkstyle y formato

| Aspecto | Convención |
|---|---|
| Formateador | **Propuesta pendiente de confirmación** — Spotless con `google-java-format` como base, ejecutado en CI antes de Checkstyle |
| Checkstyle | Configuración basada en `google_checks.xml` como punto de partida, ajustada para permitir el estilo de nombrado de la sección 4 |
| Longitud de línea | 120 caracteres (ajustado desde el default de 100, para permitir nombres de clase largos propios del patrón `UseCase`/`Command`/`Port`) |
| Imports | Sin wildcard imports (`import java.util.*`), orden alfabético |
| Ejecución | Linter/formatter corre en CI en cada PR; sin excepciones manuales (NFR-MAINT-02) |

## 7. Convenciones de value objects e inmutabilidad

- Value objects de dominio (`Percentage`, `SalaryRange`, `AlgorithmVersion`, etc.) se implementan preferentemente como `record` de Java, inmutables por diseño.
- Entidades de dominio exponen comportamiento (métodos que encapsulan invariantes), no solo getters/setters — evitar el anti-patrón de "anemic domain model" dentro de `domain/model/`.

## 8. Manejo de excepciones

- Excepciones de dominio (p. ej. violación de una invariante como BR-JOB-02) se definen en `domain/` como excepciones específicas del contexto, extendiendo tipos base de `shared-kernel` — nunca excepciones genéricas de framework lanzadas desde el dominio.
- La traducción de una excepción de dominio al formato de error de `api-contract-overview.md` §6 ocurre en `infrastructure/adapter/in/web/`, no antes.

## 9. Documentación de API, rate limiting y observabilidad (ADR-0010 a 0012)

- **OpenAPI:** todo método de `infrastructure/adapter/in/web/*Controller` lleva `@Operation(summary = ...)` con descripción de negocio (no repetir el nombre del método) y `@ApiResponse` por cada código de estado posible (`api-contract-overview.md` §6); los DTOs de request/response llevan `@Schema(description = ...)` en cada campo no obvio.
- **Rate limiting:** la política aplicable a un endpoint se declara de forma explícita (anotación propia, p. ej. `@RateLimited(policy = "apply")`, o configuración equivalente) — nunca implícita ni heredada por convención de paquete, para que sea auditable con un `grep` sobre el código qué endpoints tienen qué política.
- **Métricas de negocio:** se registran vía `MeterRegistry` inyectado en el caso de uso (`application/usecase/`), nunca en `infrastructure/adapter/in/web/` — la métrica de negocio pertenece a la lógica que la genera, no a la capa de transporte. Nombrado: `<módulo>.<entidad>.<evento>` en snake_case (p. ej. `job.eligibility.rejected`), con tags para dimensiones (p. ej. `rule_type`).
- **Logging estructurado:** todo log de negocio incluye `traceId` (vía MDC, ya presente en el contexto de la request) — nunca se construye manualmente un identificador de correlación alternativo.

## 10. Preguntas abiertas

- ¿Se confirma Spotless + `google-java-format` como formateador, o se prefiere una configuración de Checkstyle sin formateador automático adicional?
- ¿Se adopta alguna convención adicional para nombrar eventos de dominio más allá del sufijo `Event` ya usado en `domain-model.md` §6?
- ¿La anotación de rate limiting (sección 9) se implementa como anotación custom con un `HandlerInterceptor`, o como filtro (`OncePerRequestFilter`) configurado declarativamente por ruta? Afecta si la política es visible directamente sobre el método del controlador o en un archivo de configuración separado.
