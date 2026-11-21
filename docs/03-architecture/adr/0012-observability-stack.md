# ADR-0012: Stack de observabilidad (métricas, trazas, logging estructurado)
## Sistema Inteligente de Análisis de Compatibilidad CV-Vacante con CRM de Postulaciones

| Campo | Valor |
|---|---|
| **Estado** | 🟡 En revisión |
| **Versión** | 1.0 |
| **Fecha** | Agosto 2026 |
| **Documento relacionado** | NFR & Quality Attributes, System Design Document, Integration Design |

---

## Contexto

La observabilidad definida hasta ahora (NFR-OBS-01/02) cubría solo logging estructurado con `traceId` y health checks por módulo (`/actuator/health/{modulo}`) — suficiente para diagnóstico básico, pero no para "observabilidad óptima": no había métricas de negocio/técnicas, ni trazabilidad de una request a través de los módulos que toca (p. ej. postulación → `job` → `cv` → `ats`), ni un mecanismo de agregación/visualización.

## Decisión

Stack de observabilidad basado enteramente en herramientas **open-source, autohospedables vía Docker** (consistente con la restricción de infraestructura operable por un desarrollador único, `system-design-document.md` §7):

| Pilar | Herramienta | Detalle |
|---|---|---|
| **Métricas** | Spring Boot Actuator + Micrometer, expuestas en formato Prometheus (`/actuator/prometheus`) | Métricas técnicas estándar (JVM, HTTP, pool de conexiones) más métricas de negocio custom (p. ej. `matching.score.calculated.count`, `job.eligibility.rejected.count`, `applications.submitted.count`, tagueadas por módulo) |
| **Recolección y almacenamiento** | Prometheus, como servicio adicional en `docker-compose` (desarrollo/staging) | Scrape periódico de `/actuator/prometheus`; retención acotada, sin pretender ser un almacén de métricas de largo plazo |
| **Visualización** | Grafana, con dashboards versionados como código (JSON en el repo, `infra/observability/`) | Un dashboard por módulo de negocio más uno transversal (salud del sistema); no configuración manual no versionada |
| **Trazabilidad** | `traceId`/`spanId` de extremo a extremo vía MDC (Mapped Diagnostic Context) de SLF4J, propagado entre módulos dentro del mismo proceso — **no** se adopta OpenTelemetry completo en el MVP | Suficiente para un monolito de un solo proceso (ADR-0001): correlacionar logs de una misma request no requiere un colector de trazas distribuidas diseñado para múltiples servicios de red |
| **Logging estructurado** | Logback + `logstash-logback-encoder` (JSON), sin PII (BR-DATA-01, NFR-OBS-01) | Cada línea de log incluye `traceId`, `accountId` (identificador, no dato personal), `module`, `timestamp`, nivel — parseable por herramientas de agregación futuras sin cambiar el formato |
| **Alertas** | Reglas de alerta de Prometheus (`Alertmanager`), umbrales iniciales ligados a los NFR ya definidos (p. ej. NFR-PERF-01/02, NFR-AVAIL-01/02) | Alertas notifican por el canal disponible del desarrollador único (correo/webhook) — sin guardia rotativa, dado el contexto de tesis |

## Alternativas consideradas

| Alternativa | Por qué se rechazó |
|---|---|
| OpenTelemetry completo (SDK + Collector + backend de trazas tipo Jaeger/Tempo) | Diseñado para trazar llamadas de red entre servicios independientes; en un monolito de un solo proceso (ADR-0001), el mismo resultado de correlación se logra con `traceId` propagado vía MDC, sin la complejidad operativa de un colector adicional. Revisitable si el sistema migrara a microservicios en el futuro (fuera de alcance, ADR-0001) |
| Plataforma de observabilidad gestionada (Datadog, New Relic, Grafana Cloud) | Costo recurrente no justificado para un proyecto de tesis con presupuesto de infraestructura acotado (`project-charter.md` §10, pregunta abierta sobre presupuesto); el stack autohospedado cubre el mismo alcance sin costo de licencia |
| Solo logging, sin métricas ni dashboards (mantener el diseño original NFR-OBS-01/02) | No cumple "observabilidad óptima" pedida explícitamente — logs solos no permiten detectar degradación de tendencias (p. ej. aumento gradual de latencia de matching) sin revisar logs manualmente |

## Consecuencias

**Positivas**

- Métricas de negocio (no solo técnicas) permiten validar tendencias relevantes para la tesis — p. ej. tasa de rechazo por elegibilidad, volumen de sugerencias, sin depender únicamente de las métricas de éxito medidas manualmente con la cohorte (`nfr-quality-attributes.md` §9).
- Dashboards versionados como código evitan que la observabilidad dependa de configuración manual no reproducible entre ambientes.
- Costo de infraestructura adicional acotado: dos servicios más en `docker-compose` (Prometheus, Grafana), sin licencias.

**Negativas / trade-offs aceptados**

- Sin trazas distribuidas reales (solo correlación por `traceId` en logs) — aceptable mientras el sistema sea un monolito; sería una limitación real si se migrara a microservicios.
- Prometheus + Grafana + Alertmanager son tres servicios adicionales que un desarrollador único debe operar y mantener, sumados a Postgres/Redis/Ollama ya existentes — presupuestado como parte del riesgo de infraestructura ya documentado en `system-design-document.md` §8.

## Preguntas abiertas

- ¿El stack de observabilidad corre también en producción (Supabase + hosting a definir), o el alcance de Prometheus/Grafana se limita a desarrollo/staging, con producción cubierta solo por los health checks y logs estructurados de NFR-OBS-01/02?
- ¿Qué canal concreto de notificación usa Alertmanager (correo, Slack, webhook genérico) dado que no hay equipo de guardia?
- ¿Qué retención de métricas en Prometheus es suficiente para la operación continua del producto (más allá de una ventana de validación acotada), sin necesitar almacenamiento de largo plazo (p. ej. Thanos/Mimir)?
