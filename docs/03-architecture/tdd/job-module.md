# TDD — `job-module`
## Sistema Inteligente de Análisis de Compatibilidad CV-Vacante con CRM de Postulaciones

| Campo | Valor |
|---|---|
| **Estado** | 🟡 En revisión |
| **Versión** | 1.0 |
| **Fecha** | 23-nov-2026 |
| **Documentos relacionados** | ADR-0007 (modelo genérico `ruleType`/`criteria`), ADR-0016 (bandeja de salida), ADR-0027 (migraciones), `domain-model.md` §4.1, `data-model.md` §4.6, §4.11 y §4.13 a §4.15, `tdd/cv-module.md` §7 y §3.1, BR-JOB-01 a 21, BR-ELIG-01 a 13, BR-APPQ-01 a 09, BR-FUP-01 a 05, BR-VIS-07 y 08, BR-MATCH-08 |

---

## 1. Qué cubre este documento

El *Technical Design Document* del módulo `job`: la vacante, su ciclo de vida, la elegibilidad, la invitación directa y las preguntas de postulación. Las reglas ya están en `business-rules.md` §3 a §3.4 y el modelo en `domain-model.md` y `data-model.md`; aquí se fija **cómo se construye**, módulo por módulo y por ola, porque `job` crece en las tres:

| Ola | Qué entrega `job` | Bloque de la hoja de ruta |
|---|---|---|
| **1** | La vacante y su ciclo de vida **manual** (`DRAFT → PUBLISHED ⇄ CLOSED → ARCHIVED → DELETED`), requisitos estructurados, pausa de postulaciones y etapa de reclutamiento | «Vacante — ciclo de vida manual» (2 sprints) |
| **2** | Reglas de elegibilidad, invitación directa, preguntas de postulación con vínculo al perfil y solicitudes de información adicional | Ola 2 (screening) |
| **3** | Automatización: transiciones programadas y encadenadas, y archivado automático | Ola 3 (lifecycle) |

Este documento **nombra las carpetas de casos de uso** de `job` (hoy solo existen las tres que el repositorio ya comprometió: `validatejobeligibility`, `archiveexpiredclosedjobpostings` y `executescheduledjobpostingtransitions`). El detalle de sub-pasos de la Ola 1 es lo que se puede implementar ya; lo de las olas 2 y 3 queda diseñado aquí **y se reconfirma al empezar cada ola**, porque las decisiones de dependencias (taxonomía, perfil) pueden haberse movido.

## 2. Reparto de responsabilidades

| Pieza | Dónde | Por qué allí |
|---|---|---|
| Máquina de estados y sus invariantes | `job`, `domain.model` | Lógica de negocio sin Spring (ARCH-01); una transición inválida no debe poder construirse |
| Evaluación de reglas de elegibilidad | `job`, `domain.service` | Función pura `(reglas, datos del candidato) → incumplimientos`; ver §6 |
| Casos de uso y puertos | `job`, `application.usecase.<caso>` | Un caso de uso por carpeta; el límite transaccional es el caso de uso |
| Lectura del perfil del candidato | `infrastructure.adapter.out.cv` | Usa la lectura pública de `cv` (`tdd/cv-module.md` §7) y la traduce a la proyección propia de `job`; `job` nunca lee las tablas de `cv` |
| Persistencia | `infrastructure.adapter.out.persistence` | JDBC, como en `auth` y `cv` |
| Controladores | `infrastructure.adapter.in.web` | JSON y HTMX; ARCH-08 |
| Lotes de archivado y de transiciones | `infrastructure.adapter.in.scheduled` | `@Scheduled` solo en infraestructura (ARCH-09); el caso de uso no sabe que lo dispara un reloj |

## 3. Modelo de dominio

Un solo agregado grande, `JobPosting`, con entidades hijas que **no** son agregados propios (`domain-model.md` §4):

| Parte | Contenido | Invariantes principales |
|---|---|---|
| `JobPosting` (raíz) | dueño (`recruiterAccountId`), título, descripción, modalidad, país, rango salarial, estado, `acceptingApplications`, `recruitingStage`, fechas de cierre y archivado | Solo el dueño actúa (BR-JOB-01, BR-VIS-03); el estado solo cambia por una transición válida; un rango salarial lleva moneda y periodo (BR-JOB-21) |
| `JobRequirement` | habilidad (enlazada o en texto), nivel mínimo, obligatoria o deseable | Publicar exige título, descripción y **al menos un requisito estructurado** (BR-JOB-02) |
| `EligibilityRule` (Ola 2) | `ruleType` del catálogo cerrado, `criteria`, `hideIfNotEligible`, `active` | El catálogo de tipos es cerrado y excluye edad, género y categorías protegidas (BR-ELIG-01) |
| `CandidateInvitation` (Ola 2) | candidato, reclutador que invita | Una por candidato y vacante; solo el dueño la crea (BR-ELIG-11) |
| `ApplicationQuestion` (Ola 2) | tipo, opciones, obligatoria, vínculo a un campo del perfil | El vínculo manual o sugerido lo confirma siempre el reclutador (BR-APPQ-03/04) |
| `ScheduledTransition` (Ola 3) | origen, destino, fecha, estado | A lo sumo una pendiente por destino (BR-JOB-15); `ARCHIVED` nunca es programable (BR-JOB-14) |

### 3.1 La máquina de estados

La tabla de transiciones es **la única fuente** de qué puede pasar y se prueba exhaustivamente (cada par `(origen, destino)` posible, válido o no):

| Desde | Hacia | Quién | Regla |
|---|---|---|---|
| `DRAFT` | `PUBLISHED` | dueño | BR-JOB-02, BR-JOB-12 |
| `DRAFT` | `DELETED` | dueño | descartar borrador |
| `PUBLISHED` | `CLOSED` | dueño | BR-JOB-03; inicia el conteo de archivado |
| `CLOSED` | `PUBLISHED` | dueño | BR-JOB-07; cancela el conteo |
| `CLOSED` | `ARCHIVED` | sistema | BR-JOB-06, BR-JOB-14: automática y no programable |
| `ARCHIVED` | `DELETED` | dueño | BR-JOB-08, BR-JOB-09: borrado lógico (*tombstone*), nunca físico |
| `DELETED` | — | nadie | terminal |

`acceptingApplications` (BR-JOB-10) y `recruitingStage` (BR-JOB-04: `HIRING` o `REVIEWING`) son **ejes independientes** del estado y solo tienen efecto con la vacante `PUBLISHED`; no inician el conteo de archivado.

**Visibilidad (BR-JOB-11).** El listado general de candidatos solo incluye `PUBLISHED` y `CLOSED` (esta última marcada). `DRAFT`, `ARCHIVED` y `DELETED` no aparecen para nadie, y el filtro por elegibilidad (BR-ELIG-04) o la excepción de invitación (BR-VIS-08) se aplican **después** de esa regla, nunca antes.

## 4. Casos de uso

Carpetas de `job-module/src/main/java/com/usac/jobseeker/job/application/usecase/`.

### 4.1 Ola 1: vacante con ciclo de vida manual

| Carpeta | Qué hace | Capacidad | Evento |
|---|---|---|---|
| `createjobposting` | Crea la vacante en `DRAFT` con título, descripción, modalidad, país y rango salarial opcional | reclutador | — |
| `updatejobposting` | Edita una vacante propia (en `DRAFT`, `PUBLISHED` o `CLOSED`) con control de versión | reclutador, dueño | `JobPostingUpdated` si ya estaba publicada (BR-MATCH-03) |
| `managejobrequirements` | Añade, modifica y quita requisitos estructurados | reclutador, dueño | `JobPostingUpdated` |
| `transitionjobposting` | Ejecuta una transición manual válida de §3.1; compara y cambia el estado de forma atómica | reclutador, dueño | `JobPostingPublished`, `…Closed`, `…Reopened`, `…Archived`, `…Deleted` |
| `setacceptingapplications` | Pausa o reanuda la recepción de postulaciones | reclutador, dueño | — |
| `setrecruitingstage` | Cambia `HIRING` / `REVIEWING` | reclutador, dueño | `JobPostingRecruitingStageChanged` (BR-MATCH-08) |
| `getjobposting` | Una vacante, para su dueño o para un candidato **según su visibilidad** | cualquiera | — |
| `listjobpostings` | Listado propio del reclutador y listado general del candidato (§5) | cualquiera | — |
| `getjobsignals` | Lectura para `matching`: requisitos, país, modalidad y salario con moneda y periodo, **sin** datos del reclutador | interna | — |

### 4.2 Ola 2: screening

| Carpeta | Qué hace | Evento |
|---|---|---|
| `manageeligibilityrules` | Crea, modifica, activa y desactiva reglas de una vacante propia; no es retroactivo (BR-ELIG-07) | `JobEligibilityRuleChanged` |
| `validatejobeligibility` (existe) | Evalúa las reglas activas contra el perfil del candidato; devuelve **qué reglas incumple** (BR-ELIG-03). Respeta la invitación (BR-ELIG-10) y trata el dato ausente como incumplido (BR-ELIG-08) | — |
| `invitecandidate` | El dueño invita a un candidato, saltándose **todas** las reglas activas para esa pareja (BR-ELIG-09 a 13) | `CandidateInvited` |
| `revokeinvitation` | Revoca sin efecto retroactivo (BR-ELIG-13) | — |
| `manageapplicationquestions` | Configura las preguntas y su vínculo manual a un campo del perfil | — |
| `suggestquestionlink` | Sugiere el vínculo por similitud de embeddings (BR-APPQ-04); **nunca** lo aplica: lo confirma el reclutador | — |

Las solicitudes de información adicional (BR-FUP) viven en `ats`, no aquí: son sobre una **postulación**, no sobre una vacante.

### 4.3 Ola 3: automatización

| Carpeta | Qué hace |
|---|---|
| `scheduletransition` y `canceltransition` | Programa, reprograma y cancela una transición; valida el orden cronológico de las cadenas (BR-JOB-12 a 20) |
| `executescheduledjobpostingtransitions` (existe) | Ejecuta las vencidas; si el estado ya no es el esperado, la **omite** (`SKIPPED`) y nunca fuerza (BR-JOB-16) |
| `archiveexpiredclosedjobpostings` (existe) | Archiva las `CLOSED` vencidas según `archivalDelayDays`, configurable y no escrita en el código (BR-JOB-06) |

## 5. Reglas de diseño que cruzan casos de uso

1. **Toda transición es compare-and-set.** El cambio de estado se escribe con `WHERE status = :esperado` (y la versión): el primero gana y el otro recibe `409 STALE_STATE`. Es lo que hace seguras a la vez la acción manual y los lotes de la Ola 3, y reutiliza los errores compartidos de `common` (`tdd/cv-module.md` §10.1).
2. **El dueño sale del token, nunca del cuerpo ni de la ruta.** Una vacante de otro reclutador **no existe** para quien no es su dueño: `404`, no `403` (no se confirma que exista).
3. **La elegibilidad es un filtro, no una puntuación** (BR-ELIG-06). `validatejobeligibility` no conoce `matching` ni le pasa nada; lo único en común es que ambos leen el perfil.
4. **El listado se filtra en SQL**, no en memoria: estado visible (BR-JOB-11) primero, después elegibilidad y excepción de invitación (BR-VIS-08). Un listado que primero trae todo y luego recorta no escala ni protege.
5. **Borrado lógico siempre** (BR-JOB-09): una vacante `DELETED` conserva su fila para no romper `Application`, `CompatibilityScore` ni el tracker del candidato, y las consultas por defecto la excluyen.
6. **Publicar es la puerta del score.** Una vacante `DRAFT` no es candidata a puntuarse ni a listarse (BR-JOB-06 y 11): `JobPostingPublished` es lo que habilita a `matching`.
7. **Eventos solo con identificadores** (ADR-0016, BR-DATA-01), en la misma transacción del cambio.

## 6. Evaluación de elegibilidad

`EligibilityEvaluator` es una función pura en `domain.service`. Para cada regla activa:

1. Si hay una invitación para el par `(candidato, vacante)`, **todas** se tratan como cumplidas (BR-ELIG-10).
2. Si el perfil no declara el dato que la regla necesita, **se incumple** (*fail-closed*, BR-ELIG-08) y el mensaje dice qué dato completar.
3. En otro caso se compara según el tipo. El operador está implícito en el `ruleType` (ADR-0007), así que cada tipo tiene su comparador y su validador de `criteria`:

| `ruleType` | Dato del perfil (de `cv`) | Comparación |
|---|---|---|
| `COUNTRY_OF_RESIDENCE` | país de residencia | pertenece a `allowedCountries` |
| `WORK_AUTHORIZATION` | países con autorización | contiene el país de la vacante, o el de `criteria` |
| `ACADEMIC_LEVEL` | nivel más alto declarado | `≥` el mínimo, según el orden del catálogo |
| `YEARS_OF_EXPERIENCE` | años de experiencia | `≥ minYears` |
| `MODALITY` | modalidad deseada | compatible con la de la vacante |
| `AVAILABILITY_SCHEDULE` | jornada preferida | coincide con la exigida |
| `LANGUAGE_PROFICIENCY` | idioma y nivel | `≥ minProficiency` en `language` |
| `RELOCATION_WILLINGNESS` | disposición a reubicarse | es verdadera |
| `SALARY_EXPECTATION` | expectativa con moneda y periodo | rango compatible, **en la misma moneda y periodo** (BR-MATCH-10); otra moneda cuenta como dato no comparable y, por BR-ELIG-08, como incumplida |

**La forma de `criteria` se valida por tipo antes de guardar la regla** (una regla mal formada rompería la evaluación en el momento de postular), con un validador por `ruleType` en la capa de aplicación. Resuelve la pregunta abierta de `data-model.md` §6.

## 7. Datos

Tablas de `job` (ADR-0027): `job_postings`, `job_requirements`, y en la Ola 2 `eligibility_rules`, `candidate_invitations`, `application_questions`; en la Ola 3 `job_posting_scheduled_transitions`. Las definiciones están en `data-model.md` (§4.6, §4.11, §4.13 a §4.15) y en `03-lld.md` §4 (`job_requirements`).

Pendientes que este diseño exige, a incorporar al modelo con el sub-paso que los use:

| # | Qué | Por qué |
|---|---|---|
| J1 | `version bigint` en `job_postings` | Bloqueo optimista y *compare-and-set* (§5, regla 1) |
| J2 | `job_postings` **sin** columnas de embedding | Los vectores son de `matching` (`data-model.md` §4.7.1); `job` solo emite los eventos que mantienen la copia `published` |
| J3 | `job_requirements` con la forma de `03-lld.md` §4 | Hoy solo está en el LLD, no en `data-model.md` (hallazgo D9 de la revisión) |

## 8. API

Rutas de ejemplo; el contrato lo genera springdoc (ADR-0010). Heredan convenciones, sobre de error, `STALE_STATE` y `VALIDATION_FAILED` de `cv` y `common`.

| Método y ruta | Caso de uso | Límite | Errores clave |
|---|---|---|---|
| `POST /api/v1/job/postings` | `createjobposting` | escritura | 400, 403 `FORBIDDEN_CAPABILITY` |
| `GET`/`PUT /api/v1/job/postings/{id}` | `getjobposting`, `updatejobposting` | lectura / escritura | 404, 409 |
| `PUT /api/v1/job/postings/{id}/requirements` | `managejobrequirements` | escritura | 400, 404 |
| `POST /api/v1/job/postings/{id}/transitions` `{to}` | `transitionjobposting` | escritura | 409 `INVALID_TRANSITION` / `STALE_STATE`, 400 si falta un requisito para publicar |
| `PUT /api/v1/job/postings/{id}/accepting-applications` y `…/recruiting-stage` | los dos `set…` | escritura | 404, 409 |
| `GET /api/v1/job/postings` | `listjobpostings` | lectura | filtros y paginación `page`, `size` |

Las rutas de la Ola 2 y 3 se concretan al empezarlas. Las pantallas HTMX (`/app/jobs/**`) se construyen una vez con el flujo completo de cada ola, no por endpoint.

## 9. Pruebas

- **Máquina de estados:** una prueba exhaustiva de **todos** los pares `(origen, destino)`: los válidos pasan y los demás fallan con `INVALID_TRANSITION`. Es la regla más fácil de romper sin darse cuenta.
- **Concurrencia:** con Postgres real, dos transiciones simultáneas desde el mismo estado tienen exactamente un ganador; lo mismo manual contra lote en la Ola 3.
- **Autorización:** el dueño y nadie más actúa sobre su vacante; la de otro es `404`; una cuenta sin capacidad de reclutador recibe `403`.
- **Visibilidad:** cada estado contra cada tipo de usuario (§3.1), incluida la excepción de invitación.
- **Elegibilidad (Ola 2):** una tabla de casos por `ruleType`, con dato ausente, invitación y moneda distinta; y una prueba de que **ninguna** regla altera nada que lea `matching` (BR-ELIG-06).
- **Contrato con `cv`:** que `validatejobeligibility` usa solo la lectura pública de `cv`.
- **Privacidad:** los eventos y los logs sin datos del reclutador ni del candidato (BR-DATA-01).
- **Mutación (PIT):** sobre el evaluador y la máquina de estados, igual que en `auth` y `cv`.

## 10. Orden de entrega (Ola 1)

| Sub-paso | Entrega | Depende de |
|---|---|---|
| 1 | Migración de `job_postings` (con J1; `closed_at` ya está en el modelo) y `job_requirements`; dominio y máquina de estados con su prueba exhaustiva | `cv` sub-paso 1 (ya hecho) |
| 2 | `createjobposting`, `updatejobposting`, `getjobposting` y el controlador JSON | 1 |
| 3 | `managejobrequirements` | 1, ADR-0019 (enlace de habilidades; hasta entonces, texto libre) |
| 4 | `transitionjobposting`, `setacceptingapplications`, `setrecruitingstage` y sus eventos | 2 |
| 5 | `listjobpostings` con el filtro de visibilidad en SQL | 4 |
| 6 | `getjobsignals` para `matching` | 3, TDD de `matching` |

La Ola 2 empieza por `manageeligibilityrules` y `validatejobeligibility` (que ya tienen carpeta), después la invitación y por último las preguntas; la Ola 3, por el archivado.

## 11. Decisiones abiertas

| ID | Pregunta | Recomendación |
|---|---|---|
| **D1** | ¿Quién puede publicar con capacidad de reclutador **no verificada**? BR-JOB-01 solo exige la capacidad, pero el bloque «Verificación de reclutador y cuotas» (hallazgo F1) cambia esto | Permitir crear y editar borradores sin verificación, y exigirla para **publicar**; se confirma con ese bloque |
| **D2** | ¿Qué pasa con las vacantes publicadas de un reclutador que desactiva su capacidad? Sigue abierto en `domain-model.md` §8 | Mantenerlas bajo su gestión hasta que las transicione él; revisar al diseñar la desactivación de capacidades en `auth` |
| **D3** | ¿Una invitación vence o es permanente mientras la vacante esté abierta? | Permanente mientras la vacante lo esté (BR-ELIG-13 ya cubre la revocación manual) |
| **D4** | ¿Se unifican en un solo proceso periódico el archivado y las transiciones programadas? | Un caso de uso por preocupación, **un mismo planificador** que los dispara: no se duplica infraestructura y no se acopla la lógica |
| **D5** | `archivalDelayDays`: ¿archivo de configuración o tabla editable? | Configuración (`jobseeker.job.archival-delay-days`): cambiarla es un despliegue y evita una tabla de propósito general |

## 12. No cubierto todavía

- Las pantallas HTMX y la búsqueda de vacantes (ADR-0023, texto completo y vector).
- La normalización de requisitos contra la taxonomía (ADR-0019), que llega con su sub-paso en `cv`.
- Notificaciones al candidato cuando una vacante que siguió cambia de estado: ninguna regla la pide.
