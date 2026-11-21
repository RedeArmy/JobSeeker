# ADR-0011: Rate limiting configurable con Bucket4j + Redis
## Sistema Inteligente de Análisis de Compatibilidad CV-Vacante con CRM de Postulaciones

| Campo | Valor |
|---|---|
| **Estado** | 🟡 En revisión |
| **Versión** | 1.0 |
| **Fecha** | Agosto 2026 |
| **Documento relacionado** | Security Architecture, Threat Model STRIDE, API Contract Overview |

---

## Contexto

El rate limiting existía hasta ahora solo como menciones dispersas y sin mecanismo concreto: "límite de intentos/backoff (pendiente de definir)" para login (`threat-model-stride.md` §3.1), "rate limiting sobre el flujo de recalculo forzado" y "rate limiting... por consulta de sugerencia" en `matching-module` (§3.3) — cada uno como control ad-hoc, sin una estrategia transversal. El negocio exige ahora un mecanismo de rate limiting **configurable**, no hardcodeado, aplicable de forma consistente a todo el sistema.

## Decisión

**Bucket4j sobre Redis** (ya presente en el stack para cache de scores, ADR/`system-design-document.md` §4.3) como mecanismo de rate limiting distribuido, con algoritmo de **token bucket**.

| Aspecto | Detalle |
|---|---|
| Alcance de aplicación | Un interceptor/filtro transversal (`infrastructure/config/` de cada módulo, o un filtro compartido en `shared-kernel`) — no una implementación ad-hoc por endpoint |
| Clave de bucket | Combinación de `accountId` (si autenticado) o IP (si no autenticado, p. ej. login/registro) + identificador de la política aplicada |
| Configuración | Límites (capacidad del bucket, tasa de recarga) definidos en configuración de aplicación (`application.yml`), **no hardcodeados** — mismo criterio que `archivalDelayDays` (BR-JOB-06) y los pesos de scoring (BR-MATCH-05); permite ajustar sin redeploy de código, solo de configuración |
| Políticas iniciales | `login`: estricto, por IP + por cuenta (mitiga fuerza bruta, `threat-model-stride.md` §3.1); `apply`/mutaciones generales: moderado, por cuenta; `matching-suggestions`/lectura de listados: más permisivo, por cuenta — valores concretos a calibrar, no fijados en este ADR |
| Respuesta al exceder el límite | `429 Too Many Requests`, con el sobre de error estándar de `api-contract-overview.md` §6 (código `RATE_LIMIT_EXCEEDED`) más encabezados `Retry-After` y `X-RateLimit-*` |
| Excepciones | Los batches internos de `job-module` (`system-design-document.md` §4.6) no pasan por este filtro — no son tráfico de cliente |

## Alternativas consideradas

| Alternativa | Por qué se rechazó |
|---|---|
| Rate limiting en memoria (p. ej. Guava `RateLimiter`, sin Redis) | No sobrevive a un reinicio del proceso ni sería consistente si en el futuro se corre más de una instancia; Redis ya es una dependencia existente, no una nueva pieza de infraestructura |
| Rate limiting a nivel de infraestructura (p. ej. API Gateway, Nginx `limit_req`) | Añade un componente de infraestructura nuevo fuera del monolito, y no permite políticas por `accountId`/capacidad de negocio (solo por IP/ruta), que es un requisito explícito (límite configurable, no solo por IP) |
| Límites hardcodeados por endpoint en el código | Contradice el principio ya establecido en el proyecto de que toda constante de negocio/operación es configurable (BR-MATCH-05, BR-JOB-06); un ajuste de límite requeriría redeploy |

## Consecuencias

**Positivas**

- Un solo mecanismo transversal, no controles ad-hoc repetidos por módulo — resuelve de forma unificada las tres menciones dispersas de `threat-model-stride.md`.
- Reutiliza Redis, sin nueva infraestructura.
- Configurable en runtime (vía configuración de aplicación), calibrable durante la fase de validación igual que otros parámetros del sistema.

**Negativas / trade-offs aceptados**

- Cada request autenticado incurre en una consulta a Redis adicional (latencia marginal); aceptable dado que Redis ya está en el camino caliente de `matching-module`.
- Requiere definir y calibrar las políticas concretas por endpoint — este ADR fija el mecanismo, no los valores finales (ver Preguntas abiertas).

## Preguntas abiertas

- ¿Los valores concretos de capacidad/tasa de recarga por política (login, apply, sugerencias, etc.) se calibran antes del cierre de la Ola 1, o se ajustan empíricamente con tráfico real en la Ola 3 (`roadmap-and-release-plan.md` §5)?
- ¿Existe una política diferenciada para cuentas con ambas capacidades (ADR-0008) — p. ej. límites separados para sus acciones como candidato vs. como reclutador, o un único límite por cuenta sin distinción de capacidad activa?
