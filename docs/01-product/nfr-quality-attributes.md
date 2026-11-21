# NFR — Non-Functional Requirements & Quality Attributes
## Sistema Inteligente de Análisis de Compatibilidad CV-Vacante con CRM de Postulaciones

| Campo | Valor |
|---|---|
| **Estado** | 🟡 En revisión |
| **Versión** | 1.1 — §5 ajustada según ADR-0020 (NFR-SEC-01, NFR-SEC-07 y nuevo NFR-SEC-08) |
| **Fecha** | Agosto 2026 · actualizado en noviembre 2026 |
| **Documentos relacionados** | PRD-MVP, System Design Document |

---

## 1. Propósito

Requisitos de calidad del sistema, expresados como escenarios medibles (formato estímulo → respuesta), siguiendo el estándar usado en revisiones de arquitectura de empresas grandes (basado en ISO/IEC 25010 y el modelo de atributos de calidad del SEI).

## 2. Rendimiento (Performance)

| ID | Escenario | Estímulo | Respuesta esperada |
|---|---|---|---|
| NFR-PERF-01 | Cálculo de score | Candidato consulta una vacante | Score visible en ≤ 2 segundos (con caché) / ≤ 5 segundos (sin caché, recalculo con Ollama) |
| NFR-PERF-02 | Carga de pipeline del reclutador | Reclutador abre el pipeline de una vacante con hasta 200 candidatos | Renderizado completo en ≤ 3 segundos |
| NFR-PERF-03 | Generación de CV | Reclutador genera un CV de marca | PDF generado en ≤ 4 segundos |
| NFR-PERF-04 | Sugerencia de candidatos (BR-MATCH-06) | Reclutador abre la vista de candidatos sugeridos (no postulados) para una vacante | Listado inicial de candidatos sugeridos renderizado en ≤ 3 segundos, sobre el conjunto de perfiles descubribles (NFR-SCAL-02) |
| NFR-PERF-05 | Formulario de postulación con prellenado (BR-APPQ-05) | Candidato abre el formulario de postulación de una vacante con preguntas configuradas | Formulario renderizado, con las respuestas vinculadas ya prellenadas desde el perfil, en ≤ 3 segundos |
| NFR-PERF-06 | Sugerencia de vínculo por IA (BR-APPQ-04) | Reclutador guarda una pregunta de texto libre sin vínculo manual a un campo del perfil | El sistema responde con una sugerencia de vínculo (o su ausencia) en ≤ 2 segundos — es una comparación de embeddings, no una generación de texto |

## 3. Escalabilidad

| ID | Escenario | Estímulo | Respuesta esperada |
|---|---|---|---|
| NFR-SCAL-01 | Concurrencia durante validación | Hasta 100 usuarios concurrentes (cohorte USAC) | Sin degradación perceptible de tiempos de respuesta |
| NFR-SCAL-02 | Volumen de datos | Hasta 10,000 perfiles y 1,000 vacantes activas | Consultas de matching mantienen tiempos de la sección 2 |
| NFR-SCAL-03 | Volumen de configuración por vacante | Hasta 20 reglas de elegibilidad y 20 preguntas de postulación por vacante (colecciones hijas de `JobPosting`, sección 4.3 de `data-model.md`) | La validación de postulación (BR-ELIG-02/03, BR-APPQ-02) se mantiene dentro de los tiempos de la sección 2, sin escaneo fuera de los índices ya definidos (`data-model.md` §5) |

## 4. Disponibilidad y fiabilidad

| ID | Escenario | Estímulo | Respuesta esperada |
|---|---|---|---|
| NFR-AVAIL-01 | Disponibilidad durante fase de validación | Uso normal del sistema por la cohorte de prueba | Uptime ≥ 99% durante el periodo de validación |
| NFR-AVAIL-02 | Falla de Ollama (servicio de IA local) | Ollama no responde | El sistema degrada de forma controlada (muestra último score calculado + aviso), no cae por completo; la sugerencia de vínculo de BR-APPQ-04 se omite sin bloquear la creación de la pregunta |
| NFR-AVAIL-03 | Caída de Clavaris (proveedor de identidad) | Clavaris no responde (caído, colgado o con 502) | Las sesiones web activas siguen sirviéndose hasta **2 h** desde su última comprobación correcta (configurable, ≤ 8 h absolutas, ADR-0020); no hay inicios de sesión nuevos; la renovación termina en ≤ 12 s (tiempos de espera) y no bloquea los hilos del servidor. Al volver Clavaris, las sesiones cuya renovación se perdió pueden pedir iniciar sesión de nuevo (esperable) |
| NFR-REL-01 | Archivado automático de vacantes (BR-JOB-06) | Una vacante `CLOSED` vence su `archivalDelayDays` | Se archiva en la siguiente ejecución del proceso programado, con un retraso máximo tolerado igual al intervalo de ejecución del batch (a definir junto con su frecuencia, ver `system-design-document.md` §4.6) — nunca se pierde ni requiere intervención manual |
| NFR-REL-02 | Ejecución de transiciones programadas (BR-JOB-16) | Una `JobPostingScheduledTransition` en `PENDING` vence su `scheduledAt` | Se ejecuta o se marca `SKIPPED` en la siguiente ejecución del batch — nunca queda `PENDING` indefinidamente pasada su fecha |

## 5. Seguridad

| ID | Escenario | Estímulo | Respuesta esperada |
|---|---|---|---|
| NFR-SEC-01 | Autenticación | Cualquier acceso a datos de perfil o vacante | Requiere la credencial válida de su superficie (ADR-0020): sesión autenticada en `/app/**` o Bearer RS256 de Clavaris con `iss`, `exp` y `aud` válidos en `/api/v1/**`; sin credencial válida, acceso denegado. Una sesión cuya cuenta Clavaris suspende o borra se cierra en ≤ 10 min (prueba de vida) o antes por webhook |
| NFR-SEC-02 | Datos sensibles (PII) | Almacenamiento de CV, datos de contacto y archivos de respuesta a preguntas de postulación | Cifrado en tránsito (TLS) y controles de acceso por rol; nunca expuestos en logs |
| NFR-SEC-03 | Aislamiento entre reclutadores | Un reclutador intenta acceder a datos de otro reclutador | Acceso denegado a nivel de autorización, no solo de UI |
| NFR-SEC-04 | Autorización por capacidad en cuenta multi-rol (BR-ACCT-04, ADR-0008) | Una cuenta con ambas capacidades (candidato y reclutador) ejecuta una acción de una de ellas | La autorización evalúa la capacidad específica requerida por la acción, sin que la otra capacidad de la misma cuenta otorgue ni quite permisos |
| NFR-SEC-05 | Carga de archivos en preguntas de postulación (`FILE_UPLOAD`, BR-APPQ-01) | Candidato adjunta un archivo como respuesta | Tipo MIME y tamaño máximo validados antes de almacenar, mismo criterio que la carga de CV (`threat-model-stride.md` §3.2) |
| NFR-SEC-06 | Rate limiting configurable (ADR-0011) | Un cliente (autenticado o no) excede el límite de tasa configurado para un endpoint | Respuesta `429` inmediata (≤ 200ms, sin llegar a ejecutar la lógica de negocio del endpoint) con el sobre de error y encabezados de `api-contract-overview.md` §10 |
| NFR-SEC-07 | Fuerza bruta de login y abuso del flujo de acceso | Múltiples intentos fallidos de login desde la misma IP/cuenta, o creación masiva de sesiones | **El límite de fuerza bruta sobre credenciales es responsabilidad de Clavaris**, donde vive el formulario de login (ADR-0020), con su propio rate limiting y bloqueo de cuenta. **En JobSeeker:** límite configurable por IP sobre el inicio del flujo OIDC, el *callback* de login (`/login/oauth2/code/*`) y la creación de sesiones, con `429` y el sobre de error estándar (ADR-0011, NFR-SEC-06). Los umbrales concretos se calibran (pregunta abierta de ADR-0011) |
| NFR-SEC-08 | Sesión web: petición cross-site (CSRF) | Un sitio externo provoca que el navegador de un usuario autenticado envíe una petición mutante (`POST/PUT/PATCH/DELETE`) a `/app/**` | Rechazada con `403` y código `CSRF_TOKEN_INVALID` cuando falta o es inválido el token CSRF (cabecera `X-CSRF-TOKEN`), además de `SameSite=Lax` y CSP estricta. **El 100 % de las rutas mutantes de `/app/**` está cubierto por una prueba automática** que verifica el `403` sin token (ADR-0020) |

Ver también BR-DATA-01 (PII nunca en logs), aplicable igual a `application_answers.answer_value` cuando contiene datos personales en texto libre.

## 6. Usabilidad

| ID | Escenario | Estímulo | Respuesta esperada |
|---|---|---|---|
| NFR-USA-01 | Percepción general de usabilidad | Evaluación SUS con cohorte de validación | Puntaje SUS ≥ 72 |
| NFR-USA-02 | Comprensión del score | Candidato o reclutador consulta el desglose de un score | El usuario puede explicar en sus propias palabras por qué el score es el que es (validado cualitativamente en pruebas de usuario) |
| NFR-USA-03 | Comprensión del rechazo por elegibilidad (BR-ELIG-03) | Candidato intenta postular y incumple una o más reglas de elegibilidad activas | El mensaje de rechazo identifica la(s) regla(s) incumplida(s) en lenguaje natural, sin que el candidato deba inferir la causa |

## 7. Mantenibilidad

| ID | Escenario | Estímulo | Respuesta esperada |
|---|---|---|---|
| NFR-MAINT-01 | Cobertura de pruebas | Cambio en lógica de negocio de cualquier módulo | Cobertura de tests unitarios ≥ 80% en esa lógica |
| NFR-MAINT-02 | Consistencia de estilo | Cualquier código nuevo | Pasa linter/formatter (Checkstyle backend, Biome-equivalente si aplica) sin excepciones manuales |
| NFR-MAINT-03 | Documentación de API (ADR-0010) | Cualquier endpoint HTTP nuevo o modificado | Anotado con `@Operation`/`@ApiResponse`/`@Schema` con descripción de negocio; el spec OpenAPI exportado pasa el linter de CI (`api-contract-overview.md` §11) sin excepciones — un endpoint sin documentar no pasa `definition-of-done.md` |

## 8. Observabilidad (ADR-0012)

| ID | Escenario | Estímulo | Respuesta esperada |
|---|---|---|---|
| NFR-OBS-01 | Diagnóstico de fallas | Error en cualquier módulo | Log estructurado JSON con `traceId` correlacionable de extremo a extremo entre los módulos que participan de la misma request, sin exponer PII |
| NFR-OBS-02 | Salud del sistema | Consulta a endpoint de health check | Respuesta por módulo (`/actuator/health/{modulo}`) en ≤ 1 segundo |
| NFR-OBS-03 | Métricas de negocio y técnicas | Consulta a `/actuator/prometheus` | Expone, como mínimo, métricas técnicas estándar (JVM, HTTP, pool de conexiones) y las métricas de negocio definidas por módulo (p. ej. postulaciones creadas, rechazos por elegibilidad, scores calculados), tagueadas por módulo |
| NFR-OBS-04 | Detección de degradación | Un NFR de rendimiento (sección 2) se incumple de forma sostenida (p. ej. NFR-PERF-01 por encima de umbral durante > 5 minutos) | Una regla de alerta de Prometheus/Alertmanager notifica antes de que la cohorte de validación reporte el problema manualmente |
| NFR-OBS-05 | Cobertura de instrumentación | Nuevo endpoint o caso de uso agregado a cualquier módulo | Expone al menos una métrica de negocio propia y logging estructurado con `traceId`, verificado como criterio de `definition-of-done.md`, no como buena práctica opcional |

## 9. Satisfacción y adopción (vinculadas a métricas de tesis)

| ID | Escenario | Estímulo | Respuesta esperada |
|---|---|---|---|
| NFR-ADOPT-01 | Retención de la cohorte de validación | Uso del sistema durante 30 días | ≥ 60% de usuarios activos al día 30 |
| NFR-ADOPT-02 | Satisfacción general | Encuesta NPS a la cohorte de validación | NPS ≥ 40 |
