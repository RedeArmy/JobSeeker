# TDD — `job-module`
## Sistema Inteligente de Análisis de Compatibilidad CV-Vacante con CRM de Postulaciones

| Campo | Valor |
|---|---|
| **Estado** | 🟡 En revisión |
| **Versión** | 1.1 (sub-pasos 1 a 4 y 6 de la Ola 1 construidos, §10.1) |
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
| J1 | ✅ `version bigint` en `job_postings` | Bloqueo optimista y *compare-and-set* (§5, regla 1) |
| J2 | `job_postings` **sin** columnas de embedding | Los vectores son de `matching` (`data-model.md` §4.7.1); `job` solo emite los eventos que mantienen la copia `published` |
| J3 | ✅ `job_requirements`, con columnas por tipo en vez de `criteria jsonb` (§10.1) | Estaba solo en el LLD, no en `data-model.md` (hallazgo D9 de la revisión) |

## 8. API

Rutas de ejemplo; el contrato lo genera springdoc (ADR-0010). Heredan convenciones, sobre de error, `STALE_STATE` y `VALIDATION_FAILED` de `cv` y `common`.

| Método y ruta | Caso de uso | Límite | Errores clave |
|---|---|---|---|
| `POST /api/v1/job/postings` | `createjobposting` | escritura | 400, 403 `FORBIDDEN_CAPABILITY` |
| `GET`/`PUT /api/v1/job/postings/{id}` | `getjobposting`, `updatejobposting` | lectura / escritura | 404, 409 `STALE_STATE` / `INVALID_STATE` |
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
| 1 | ✅ Migración de `job_postings` (con J1; `closed_at` ya está en el modelo) y `job_requirements`; dominio y máquina de estados con su prueba exhaustiva | `cv` sub-paso 1 (ya hecho) |
| 2 | ✅ `createjobposting`, `updatejobposting`, `getjobposting` y el controlador JSON | 1 |
| 3 | ✅ `managejobrequirements` (habilidades en texto libre hasta ADR-0019) | 1, ADR-0019 (enlace de habilidades; hasta entonces, texto libre) |
| 4 | ✅ `transitionjobposting`, `setacceptingapplications`, `setrecruitingstage` y sus eventos | 2 |
| 5 | ⏳ `listjobpostings` con el filtro de visibilidad en SQL | 4 |
| 6 | ✅ `getjobsignals` para `matching` (la lectura; el consumo en `matching` es TD-028) | 3, TDD de `matching` |

### 10.1 Sub-pasos 1 a 4 y 6: qué se construyó y en qué se desvió de este documento

Se construyó la vacante con su ciclo de vida manual de punta a punta: migración, dominio, ocho casos de uso, el controlador JSON y la lectura para `matching`. Decisiones que la primera versión no tenía:

- **La máquina de estados es una tabla con iniciador.** `JobStatus.canMoveTo(destino, iniciador)` distingue `OWNER` de `SYSTEM`: `CLOSED → ARCHIVED` existe pero solo la dispara el sistema (BR-JOB-14), así que pedirla por la API da `409 INVALID_TRANSITION`. La prueba recorre los 50 pares `(origen, destino, iniciador)` y solo pasan los seis de §3.1.
- **Dos errores nuevos en `common`.** `INVALID_TRANSITION` (el par no existe o no lo puede hacer quien lo pide) e `INVALID_STATE` (editar una archivada, pausar las postulaciones de una cerrada). Ambos son 409 y distintos de `STALE_STATE`: volver a leer y reintentar no los arregla.
- **Toda escritura es *compare-and-set* sobre la versión**, y la versión sube con **cada** cambio (datos, requisitos, estado, ejes). Pedir lo que ya está guardado no sube la versión ni emite evento (BR-MATCH-03). Dos cierres simultáneos tienen exactamente un ganador, con Postgres real.
- **Publicar exige una habilidad o una formación** (BR-JOB-02 lo dice así; §3 decía «un requisito estructurado» a secas). Solo años de experiencia o un idioma no alcanzan, porque no bastan para puntuar. La regla vale también para seguir publicada: reemplazar los requisitos de una vacante publicada por un conjunto sin habilidad ni formación es `400`.
- **`job_requirements` con columnas por tipo, no `criteria jsonb`** (resuelve J3). El conjunto de tipos es cerrado y pequeño, y un `CHECK` por tipo hace que la base rechace un requisito mal formado o con columnas de otro tipo. El modelo genérico del ADR-0007 sigue siendo el de las reglas de elegibilidad, que sí es un catálogo abierto a crecer.
- **Eventos con identificador y versión, sin contenido.** `job.posting_published`, `_closed`, `_reopened`, `_archived`, `_deleted`, `_updated` y `job.recruiting_stage_changed`, con el tipo de agregado `JOB_POSTING` para que el orden de entrega sea por vacante. Un borrador no emite nada: `matching` solo se entera al publicar (§5, regla 6). Pausar las postulaciones no emite: no cambia el contenido ni el score.
- **La lectura de una vacante ajena responde `404` también a quien no es candidato**, no `403`: un `403` confirmaría que la vacante existe (§5, regla 2). Un candidato recibe además un contrato más corto, sin versión, etapa, fechas de gestión ni quién la escribió.
- **`getjobsignals` entrega todo lo que no es borrador ni eliminada**, con su estado. Una cerrada o archivada sigue teniendo señales para que los scores ya calculados se puedan explicar; `matching` decide qué hacer con cada estado.

### 10.2 La puerta de publicar (D1, TD-030)

Pasar a `PUBLISHED`, sea desde borrador o reabriendo una cerrada, cruza una puerta (`PublishingGate`) que mira la confianza en la cuenta y cuántas vacantes publicadas tiene ya (BR-JOB-22). Decisiones:

- **La verificación es de `auth`, no de `job`.** Es un atributo de la cuenta junto a sus capacidades (`accounts.recruiter_verification`, BR-ACCT-10) y `job` la pregunta por el caso de uso `getrecruiterverification`, con un puerto propio y un enumerado propio (ARCH-04). El `RecruiterProfile` de `cv` (Ola 3) es el perfil público de la cuenta y no es el lugar de la confianza: no existe aún, y la verificación debe funcionar antes que él.
- **Tres motivos, tres códigos**, porque el cliente reacciona distinto: `403 RECRUITER_NOT_VERIFIED` (sin verificar y en su tope: verificarse lo sube), `403 RECRUITER_SUSPENDED` y `429 QUOTA_EXCEEDED` (verificada y en su tope: cerrar una libera cupo), como el LLD §438.
- **Solo cuentan las `PUBLISHED`.** Cerrar libera el cupo, que es lo que el reclutador espera, y reabrir una cerrada pasa por el mismo tope: si no, cerrar y reabrir sería una forma de saltárselo.
- **El recuento toma un candado de transacción por reclutador** (`pg_advisory_xact_lock`). Sin él, dos publicaciones simultáneas de la misma cuenta contarían ambas «una menos que el tope» y pasarían las dos. Es por cuenta, así que no frena a otros reclutadores. La prueba de punta a punta repite la carrera en 25 rondas, porque una sola casi nunca alcanza una ventana de milisegundos (con el candado quitado falla en la ronda 4).
- **Los topes son configuración** (`jobseeker.job.publishing.*`, 2 y 100 por defecto) y se rechaza al arrancar una política incoherente (verificarse no puede empeorar la cuenta).
- **La puerta va después de validar la transición**: un publicar inválido (sin habilidad, par que no existe) se dice antes que el tope, para no revelar el cupo.
- **Ante la duda no se publica**: una cuenta que `auth` no reconoce como reclutadora activa se trata como suspendida.

**Lo que esto no cierra:** quién y cómo pasa una cuenta a `VERIFIED` (correo corporativo o revisión manual, REQ-R-10), y que las sugerencias de candidatos exijan `VERIFIED` (REQ-R-10, REQ-R-11). Ambos son TD-032: el caso de uso existe y está probado, pero hoy nadie lo llama.

**Pendiente de esta ola:** `listjobpostings` (sub-paso 5), que necesita el filtro de visibilidad en SQL; el consumo de `getjobsignals` en `matching` (`JobSignals`, `job_embeddings`; TD-028); y el procedimiento que verifica a un reclutador (TD-032), porque la puerta de publicar de §10.2 ya funciona pero hoy nadie pasa una cuenta a `VERIFIED`.

La Ola 2 empieza por `manageeligibilityrules` y `validatejobeligibility` (que ya tienen carpeta), después la invitación y por último las preguntas; la Ola 3, por el archivado.

## 11. Decisiones abiertas

| ID | Pregunta | Recomendación |
|---|---|---|
| **D1** | ✅ **Resuelta (24-nov-2026).** ¿Quién puede publicar con capacidad de reclutador **no verificada**? | Se crea y edita sin verificación y se publica con un tope según la confianza en la cuenta (BR-JOB-22, §10.2). La verificación es un atributo de la cuenta (BR-ACCT-10) y vive en `auth` |
| **D2** | ¿Qué pasa con las vacantes publicadas de un reclutador que desactiva su capacidad? Sigue abierto en `domain-model.md` §8 | Mantenerlas bajo su gestión hasta que las transicione él; revisar al diseñar la desactivación de capacidades en `auth` |
| **D3** | ¿Una invitación vence o es permanente mientras la vacante esté abierta? | Permanente mientras la vacante lo esté (BR-ELIG-13 ya cubre la revocación manual) |
| **D4** | ¿Se unifican en un solo proceso periódico el archivado y las transiciones programadas? | Un caso de uso por preocupación, **un mismo planificador** que los dispara: no se duplica infraestructura y no se acopla la lógica |
| **D5** | `archivalDelayDays`: ¿archivo de configuración o tabla editable? | Configuración (`jobseeker.job.archival-delay-days`): cambiarla es un despliegue y evita una tabla de propósito general |

## 12. No cubierto todavía

- Las pantallas HTMX y la búsqueda de vacantes (ADR-0023, texto completo y vector).
- La normalización de requisitos contra la taxonomía (ADR-0019), que llega con su sub-paso en `cv`.
- Notificaciones al candidato cuando una vacante que siguió cambia de estado: ninguna regla la pide.
