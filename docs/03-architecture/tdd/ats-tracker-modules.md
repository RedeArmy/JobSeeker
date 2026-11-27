# TDD — `ats-module` y `tracker-module`
## Sistema Inteligente de Análisis de Compatibilidad CV-Vacante con CRM de Postulaciones

| Campo | Valor |
|---|---|
| **Estado** | 🟡 En revisión |
| **Versión** | 0.2 (diseño; nada construido). Decisiones D1 a D6 cerradas por el responsable el 27-nov-2026 |
| **Fecha** | 26-nov-2026 |
| **Documentos relacionados** | ADR-0016 (bandeja de salida), ADR-0017 (F6, copia del perfil al postular), ADR-0021 (archivos), ADR-0027 (dueño de tablas), ADR-0031 (puntaje guardado), `domain-model.md` §3 a §6, `data-model.md` §4.8, §4.16 y §4.17, `03-lld.md` §4.1 y §7, `tdd/job-module.md`, `tdd/cv-module.md` §7, `tdd/matching-module.md` §4, BR-TRACK-01 y 02, BR-CAND-01, BR-ACCT-05, BR-JOB-09 y 10, BR-VIS-01 a 03, BR-DATA-04 y 05, REQ-C-03, REQ-C-07, REQ-R-01 y 03, TD-010, TD-017, TD-040 |

---

## 1. Qué cubre este documento

El *Technical Design Document* de la postulación: `ats` (el pipeline del reclutador) y `tracker` (el seguimiento del candidato). Se escriben juntos porque comparten el agregado `Application` (`domain-model.md` §3) y porque decidir dónde vive cada pieza es la mitad del diseño.

| Ola | Qué entregan | Bloque de la hoja de ruta |
|---|---|---|
| **1** | El candidato postula (sin elegibilidad ni preguntas) y ve su estado; el reclutador ve el pipeline de su vacante ordenado y filtrable por puntaje, mueve candidatos de etapa, descarta o contrata, y descarga el CV recibido | «Postulación y pipeline mínimos» (3 sprints) |
| **2** | Elegibilidad al postular, invitación, preguntas de postulación con prellenado, solicitudes de información adicional, etapas configurables, notas internas | «ATS completo» y «Tracker completo» |

Este documento **nombra las carpetas de casos de uso** de los dos módulos, que hoy solo tienen esqueleto. Lo de la Ola 1 se puede implementar en cuanto exista la subida del CV (ADR-0021, aprobado el 26-nov-2026); lo de la Ola 2 queda esbozado y **se reconfirma al empezarla**.

**Precondición de la Ola 1:** el bloque «Almacenamiento de archivos» (ADR-0021). BR-CAND-01 exige un CV cargado para postular y `tdd/cv-module.md` (D1) decidió no relajarla, así que `submitapplication` no puede construirse antes que la subida del CV.

## 2. Reparto de responsabilidades

| Pieza | Dónde | Por qué allí |
|---|---|---|
| El agregado `Application`, su máquina de estados y **toda escritura** sobre postulaciones | `ats` | ADR-0027 ya asigna a `ats` las tablas `applications`, `application_answers` y las de seguimiento. Un solo escritor evita dos reglas de transición |
| Las vistas del candidato (mis postulaciones, el detalle con su historial) y sus pantallas | `tracker` | Es el contexto del candidato (`domain-model.md` §2). **No es dueño de tablas** (ADR-0027): lee por los casos de uso públicos de `ats`, nunca sus tablas |
| Postular y retirar una postulación | casos de uso de `ats`, llamados desde la API de `ats` | Son escrituras sobre el agregado. El candidato los usa, pero la regla vive con los datos |
| Quién es el dueño de la vacante y si acepta postulaciones | `job`, por un caso de uso interno nuevo (§5) | `ats` nunca lee `job_postings` |
| Si el candidato puede postular (nombre, contacto, CV) y sus datos para el reclutador | `cv`, por casos de uso internos nuevos (§5) | BR-CAND-01 es un dato de `cv`; la autorización del reclutador la hace `ats` antes de pedirlos |
| El puntaje en el pipeline y la copia del perfil al postular | `matching` (`snapshotapplicationprofile` ya existe; una lectura por lotes nueva, §5) | ADR-0031: los puntajes guardados son de `matching` |

**Desvío del modelo de dominio.** `domain-model.md` §6 dibuja a `tracker` con una **proyección** propia alimentada por `ApplicationStatusChangedEvent`. Para la Ola 1 se descarta: ADR-0027 dice que `tracker` no tiene tablas, y una proyección duplicaría el estado con su retraso. `tracker` consulta a `ats` en cada lectura (pocas filas por candidato). El evento sigue existiendo, para las notificaciones (ADR-0022).

## 3. Modelo de dominio

Un agregado, `Application`, en `ats.domain.model`. En la Ola 2 crece con `ApplicationAnswer` y `ApplicationFollowUpRequest` como entidades hijas (`domain-model.md` §3).

| Parte | Contenido | Invariantes principales |
|---|---|---|
| `Application` (raíz) | candidato (perfil y cuenta), vacante, CV con que postuló, `status`, `stage`, fechas, motivo de descarte, versión | Una sola **activa** por candidato y vacante (BR-TRACK-01); el candidato no es el dueño de la vacante (BR-ACCT-05); el CV pertenece al candidato |
| `ApplicationStatusChange` | de/a (`status`, `stage`), quién (candidato, reclutador o sistema), cuándo, motivo | Se registra **cada** cambio: es lo que hace visible el historial (BR-TRACK-02) y auditable el descarte (REQ-R-03) |

### 3.1 Estado y etapa

Dos ejes, como propone el LLD §4.1 (punto 4): un `status` grueso que dice si la postulación sigue viva, y una `stage` que dice dónde está dentro del pipeline.

| `status` | Significado | Terminal |
|---|---|---|
| `ACTIVE` | En proceso | No |
| `HIRED` | Contratado | Sí |
| `REJECTED` | Descartado por el reclutador | Para el candidato sí; el reclutador puede reabrirla (D3) |
| `WITHDRAWN` | Retirado por el candidato (REQ-C-03) | Sí |

En la Ola 1 las etapas son un **catálogo fijo**: `NEW → REVIEW → INTERVIEW → OFFER` (la plantilla por defecto de REQ-R-01). En la Ola 2 pasan a ser configurables por vacante (D2).

| Desde | Hacia | Quién | Regla |
|---|---|---|---|
| — | `ACTIVE` / `NEW` | candidato | Postular (§4.1) |
| `ACTIVE` (cualquier etapa) | `ACTIVE` (otra etapa) | reclutador dueño | Se puede avanzar o retroceder: un pipeline real vuelve atrás |
| `ACTIVE` | `REJECTED` | reclutador dueño | Con un motivo de un catálogo cerrado (REQ-R-03), nunca texto libre visible al candidato |
| `ACTIVE` | `HIRED` | reclutador dueño | Desde cualquier etapa |
| `ACTIVE` | `WITHDRAWN` | candidato dueño | REQ-C-03 |
| `REJECTED` | `ACTIVE` (la etapa que tenía) | reclutador dueño | **Reabrir** (D3, BR-TRACK-04): por ejemplo, el elegido no aceptó la oferta y se vuelve a quien ya pasó las pruebas, o a quien se descartó por no responder. Solo mientras la vacante no esté archivada ni eliminada |
| `HIRED`, `WITHDRAWN` | — | nadie | Terminales (D3) |

Como en `job`, la tabla de transiciones es **la única fuente** de qué puede pasar y se prueba exhaustivamente, cada par con cada iniciador. Toda transición es *compare-and-set* sobre la versión.

**Etapa pública y estado interno (D1, decidido por el responsable el 27-nov-2026; BR-TRACK-03).** La etapa tiene dos niveles:

- **Etapa pública**: las columnas del tablero kanban (`NEW`, `REVIEW`, `INTERVIEW`, `OFFER`). Es un catálogo **fijo, común a todos los reclutadores**, y es lo que el candidato ve en su tracker junto con el estado, con etiquetas en español (Recibida, En revisión, Entrevista, Oferta). Un final se muestra como Contratado, No continúa o Retirada.
- **Estado interno**: un estado propio del reclutador dentro de una columna (por ejemplo «Pendiente de aprobar sueldo» dentro de `OFFER`). Pertenece siempre a **exactamente una** columna, solo lo ve el reclutador y nunca sale en las respuestas ni en los eventos dirigidos al candidato. Moverlo dentro de la misma columna no es un cambio visible para el candidato (BR-TRACK-02 se refiere a la columna y al estado).

En la Ola 1 solo existe la etapa pública, con el catálogo fijo (D2, decidido). Los estados internos llegarían con las etapas configurables (REQ-R-01, `managepipelinestages`, Ola 2), previsiblemente como una tabla propia del reclutador y un `internal_stage_id` opcional en la postulación (expandir y contraer); su diseño se cierra con el análisis de esa ola (D2). Lo configurable son los estados internos; las columnas públicas siguen fijas, así el candidato ve siempre el mismo tablero sea cual sea el reclutador.

**Reabrir y repostular (D3, decidido por el responsable el 27-nov-2026; BR-TRACK-04 y BR-TRACK-05).** Un descarte es definitivo **para el candidato**, pero no para el reclutador. El reclutador dueño puede reabrir una postulación descartada, por el motivo que fuera (también la de quien dejó de responder), y vuelve a `ACTIVE` en la etapa que tenía, con su historial intacto: si había pasado las pruebas, sigue en `INTERVIEW` u `OFFER`. El candidato lo ve en su tracker como un cambio más (BR-TRACK-02). El candidato descartado **no puede volver a postular** a esa vacante, porque su proceso terminó; a esa vacante solo vuelve si el reclutador reabre su postulación. Sí puede postular, como cualquiera, a otras vacantes del mismo reclutador. Tras **retirarse** sí puede volver a postular, mientras la vacante acepte postulaciones (REQ-C-03), porque fue él quien lo dejó. Una contratación no se reabre. Lo impone un índice único parcial (§7).

## 4. Casos de uso

### 4.1 `ats`, Ola 1

Carpetas de `ats-module/src/main/java/com/usac/jobseeker/ats/application/usecase/`.

| Carpeta | Qué hace | Capacidad | Evento |
|---|---|---|---|
| `submitapplication` | El candidato postula. Comprueba que la vacante acepte postulaciones y no sea suya (`job`), que el candidato cumpla BR-CAND-01 (`cv`), y que no tenga ya una activa; crea la postulación en `ACTIVE`/`NEW` con el CV vigente; llama a `snapshotapplicationprofile` de `matching` en la misma transacción (TD-040). Idempotente con `Idempotency-Key` | candidato | `ApplicationSubmitted` |
| `withdrawapplication` | El candidato retira una postulación propia y activa | candidato, dueño | `ApplicationStatusChanged` |
| `changeapplicationstage` | El reclutador mueve una postulación activa de etapa; llama a `snapshotapplicationprofile` con `STAGE_CHANGED` (TD-040) | reclutador, dueño de la vacante | `ApplicationStatusChanged` |
| `decideapplication` | El reclutador descarta (con motivo) o contrata | reclutador, dueño de la vacante | `ApplicationStatusChanged` |
| `reopenapplication` | El reclutador reabre una postulación descartada en la etapa que tenía (D3); queda en el historial con quién y cuándo | reclutador, dueño de la vacante | `ApplicationStatusChanged` |
| `listjobapplications` | El pipeline de una vacante propia: postulaciones con puntaje, ordenadas por puntaje (o fecha) y filtrables por estado, etapa y puntaje mínimo, paginadas | reclutador, dueño de la vacante | — |
| `getapplication` | El detalle para el reclutador: datos y contacto del candidato (al haber postulado deja de estar enmascarado, REQ-C-07), desglose del puntaje e historial | reclutador, dueño de la vacante | — |
| `getapplicationcv` | Autoriza y pide la URL firmada del CV **con que se postuló** (ADR-0021 §3) | reclutador, dueño de la vacante | — |
| `listcandidateapplications` | Las postulaciones de un candidato, con su historial; **interna**, para `tracker` | interna | — |

### 4.2 `tracker`, Ola 1

Carpetas de `tracker-module/src/main/java/com/usac/jobseeker/tracker/application/usecase/`.

| Carpeta | Qué hace | Capacidad |
|---|---|---|
| `listmyapplications` | Mis postulaciones: vacante (título y estado, también si se cerró o eliminó, BR-JOB-09), `status`, `stage`, fechas. Compone `listcandidateapplications` de `ats` con la lectura de vacantes de `job` | candidato |
| `getmyapplication` | El detalle de una propia con su historial completo (BR-TRACK-02); la de otro no existe (`404`) | candidato, dueño |

`tracker` no escribe nada: retirar una postulación es `withdrawapplication` de `ats`.

### 4.3 Ola 2 (se reconfirma al empezarla)

| Módulo | Carpeta | Qué hace |
|---|---|---|
| `ats` | `submitapplication` (amplía) | Evalúa la elegibilidad con `validatejobeligibility` de `job` (respeta la invitación) y guarda las respuestas confirmadas (BR-APPQ-05 a 07) |
| `ats` | `requestfollowup`, `answerfollowup` | Solicitudes de información adicional (BR-FUP-01 a 05) |
| `ats` | `managepipelinestages` | Estados internos del reclutador (REQ-R-01), cada uno dentro de una columna pública fija; solo visibles para él (D1, BR-TRACK-03) |
| `ats` | `addapplicationnote` | Notas internas (REQ-R-02) |
| `tracker` | `getapplicationform` | El formulario con las preguntas prellenadas desde el perfil, para confirmar (BR-APPQ-05/06) |

## 5. Lo que este diseño pide a otros módulos

Casos de uso **internos** nuevos que `ats` y `tracker` consumen. Se nombran aquí porque `ats` es quien los necesita (`tdd/matching-module.md` §4 lo prevé así) y se crean con el sub-paso que los usa, en el módulo dueño del dato. Cada TDD dueño los anota en su lista.

| Módulo | Carpeta | Qué responde | Por qué no basta lo existente |
|---|---|---|---|
| `job` | `getapplicationgate` | Para (cuenta, vacante): `OPEN`, `NOT_FOUND` (no existe o no es visible para candidatos), `NOT_ACCEPTING` (cerrada o con `acceptingApplications = false`, BR-JOB-10) u `OWN_JOB` (BR-ACCT-05) | `getjobposting` devuelve al candidato un contrato sin dueño ni pausa; `getownedjobstate` solo responde al dueño |
| `job` | `getownedjobstate` (existe) | Si una vacante es del reclutador | Se reutiliza para autorizar el pipeline |
| `cv` | `checkapplicationreadiness` (existe) | Qué le falta a la cuenta para postular según BR-CAND-01 (nombre, contacto, CV), el perfil de candidato y el identificador del CV vigente | El perfil de candidato no sabe si hay CV; la regla vive con el dato |
| `cv` | `getapplicantprofiles` | Por lotes: nombre, titular y contacto de los postulantes. Solo lo llama `ats` **después** de autorizar al reclutador | `getprofilesnapshot` excluye nombre y contacto a propósito (minimización); el reclutador de una postulación sí debe verlos (REQ-C-07) |
| `cv` | `presigncvdownload` | La URL firmada de una versión concreta del CV (ADR-0021 §3), sin autorizar: autoriza quien la pide | La autorización depende de la postulación, que `cv` no conoce (ARCH-06) |
| `matching` | `snapshotapplicationprofile` (existe) | Copia del perfil y foto del puntaje al postular y al cambiar de etapa | Cierra TD-040 |
| `matching` | `getapplicantscores` | Por lotes, para una vacante y un conjunto de perfiles: el puntaje vigente con su desglose y el de la postulación | Hoy no hay lectura pública de puntajes guardados para el reclutador |
| `auth` | `resolveactor` (existe) | La capacidad del actor; una cuenta en periodo de gracia ya se rechaza (`PENDING_DELETION`), lo que cumple BR-DATA-04 («no puede postular») | — |

## 6. Reglas de diseño que cruzan casos de uso

1. **La autorización sale del token y de la vacante raíz.** Toda acción del reclutador se autoriza por el `job_posting_id` de la postulación con `getownedjobstate` (LLD §6). Una postulación de una vacante ajena **no existe**: `404`, no `403`, igual que en `job` (§5, regla 2).
2. **Una postulación es de un candidato y la ve su dueño o el dueño de la vacante.** Nadie más, ni otro reclutador (BR-VIS-03) ni otro candidato.
3. **Postular es una transacción con orden fijo:** validar (vacante, preparación, duplicado) → insertar postulación e historial → copia en `matching` → evento a la bandeja. Si falla la copia, falla la postulación: nunca hay una postulación sin la foto de lo que se presentó (ADR-0017 F6, TD-040).
4. **BR-TRACK-01 lo impone la base**, no una lectura previa: el índice único parcial convierte la carrera de dos envíos en `409 ALREADY_APPLIED`. La lectura previa solo da un mensaje mejor en el caso común.
5. **Idempotencia** (`Idempotency-Key`, ADR-0016 y LLD §7): repetir el envío devuelve la misma postulación. Es el primer endpoint que la usa y paga la parte de escritor de TD-010.
6. **El puntaje se ordena en memoria, con tope.** Los puntajes viven en `matching` y `ats` no lee sus tablas. El pipeline lee todos los identificadores de la vacante, pide sus puntajes por lote y ordena; con el tope de 1 000 postulaciones activas por consulta el costo es de milisegundos (NFR-PERF-02: 200 en ≤ 3 s). Por encima haría falta una proyección (D4).
7. **Sin PII en `ats`.** Las tablas de la Ola 1 solo guardan identificadores, estados, fechas y un motivo codificado. Nombre y contacto se piden a `cv` al mostrar. Por eso la anonimización (BR-DATA-05) no toca `ats`: la postulación se conserva y `cv` devuelve el marcador «Candidato eliminado».
8. **Eventos solo con identificadores** (ADR-0016, BR-DATA-01): `ats.application_submitted` y `ats.application_status_changed`, con tipo de agregado `APPLICATION`.
9. **La vacante eliminada no borra nada** (BR-JOB-09): el pipeline deja de existir para el reclutador (la vacante es `404`), pero el candidato sigue viendo su postulación en el tracker, marcada.

## 7. Datos

Tablas de `ats` (ADR-0027). Las claves foráneas apuntan «hacia la izquierda» de la cadena `auth → cv → job → matching → ats`, como permite el ADR-0027:

```sql
-- Ilustrativo; la migración se escribe con el sub-paso 1.
create table applications (
    id uuid primary key,
    candidate_profile_id uuid not null references candidate_profiles (id),
    candidate_account_id uuid not null references accounts (id),
    job_posting_id uuid not null references job_postings (id),
    cv_file_id uuid not null,               -- referencia a la versión del CV (ADR-0021 §5); su FK se define con la tabla de cv
    status varchar not null check (status in ('ACTIVE', 'HIRED', 'REJECTED', 'WITHDRAWN')),
    stage varchar not null check (stage in ('NEW', 'REVIEW', 'INTERVIEW', 'OFFER')),
    rejection_reason varchar check (rejection_reason in (...)),  -- catálogo cerrado, D5
    version bigint not null default 0,
    applied_at timestamptz not null,
    updated_at timestamptz not null,
    constraint ck_app_rejection check ((status = 'REJECTED') = (rejection_reason is not null))
);
-- BR-TRACK-01 y BR-TRACK-05: una sola postulación por candidato y vacante, salvo las retiradas
-- (REQ-C-03 deja repostular tras retirarse). Una descartada sigue contando: el candidato no puede
-- crear otra, y reabrirla reutiliza la misma fila (D3).
create unique index uq_app_one_per_job on applications (candidate_profile_id, job_posting_id)
    where status <> 'WITHDRAWN';
create index idx_app_job on applications (job_posting_id, status);
create index idx_app_candidate on applications (candidate_account_id, applied_at desc);

create table application_status_history (
    id uuid primary key,
    application_id uuid not null references applications (id),
    from_status varchar, to_status varchar not null,
    from_stage varchar, to_stage varchar not null,
    actor varchar not null check (actor in ('CANDIDATE', 'RECRUITER', 'SYSTEM')),
    rejection_reason varchar,
    changed_at timestamptz not null
);
```

Cambios al modelo que este diseño exige, a incorporar con el sub-paso que los use:

| # | Qué | Por qué |
|---|---|---|
| A1 | `applications` gana `candidate_account_id`, `cv_file_id`, `stage`, `version`, `updated_at` y `rejection_reason`; `status` gana su `CHECK` | `data-model.md` §4.8 solo tenía `status` sin valores; el índice único parcial ya estaba propuesto en el LLD §4.1 |
| A2 | `application_status_history`, nueva | Estaba en el ER sin diccionario (`03-lld.md` §4.3); BR-TRACK-02 y REQ-R-03 |
| A3 | El índice único es sobre `status = 'ACTIVE'`, no sobre `status <> 'WITHDRAWN'` como en el LLD §4.1 | El índice garantiza solo BR-TRACK-01 (una activa). Que un descartado no pueda volver a postular (D3) es una regla del caso de uso: si D3 cambia en la Ola 2, no hace falta otra migración |
| A4 | `candidate_account_id` además del perfil | El tracker y la autorización del candidato trabajan con la cuenta del token; sin ella habría que preguntar a `cv` el perfil en cada lectura |

## 8. API

Rutas de ejemplo; el contrato lo genera springdoc (ADR-0010) y hereda las convenciones y errores de `common`.

| Método y ruta | Caso de uso | Límite | Errores clave |
|---|---|---|---|
| `POST /api/v1/ats/jobs/{id}/applications` | `submitapplication` | escritura + `Idempotency-Key` | 404, 409 `ALREADY_APPLIED`, 409 `INVALID_STATE` (no acepta postulaciones), 403 `SELF_APPLICATION`, 422 `APPLICATION_REQUIREMENTS_MISSING` con lo que falta (BR-CAND-01) |
| `POST /api/v1/ats/applications/{id}/withdrawal` | `withdrawapplication` | escritura | 404, 409 `INVALID_TRANSITION` |
| `GET /api/v1/ats/jobs/{id}/applications?status&stage&minScore&sort&page&size` | `listjobapplications` | lectura | 404 (vacante ajena) |
| `GET /api/v1/ats/applications/{id}` | `getapplication` | lectura | 404 |
| `PUT /api/v1/ats/applications/{id}/stage` | `changeapplicationstage` | escritura | 404, 409 `INVALID_TRANSITION` / `STALE_STATE` |
| `POST /api/v1/ats/applications/{id}/decision` `{HIRED \| REJECTED, reason}` | `decideapplication` | escritura | 400 sin motivo al descartar, 409 |
| `GET /api/v1/ats/applications/{id}/cv` | `getapplicationcv` | lectura | `302` a la URL firmada; 404; 410 si el titular eliminó su cuenta |
| `GET /api/v1/tracker/applications` | `listmyapplications` | lectura | — |
| `GET /api/v1/tracker/applications/{id}` | `getmyapplication` | lectura | 404 |

**Desvío del LLD §7:** retirar es `POST …/withdrawal`, no `DELETE /applications/{id}`. Retirar no borra nada: la postulación se conserva y cambia de estado, y un `DELETE` lo diría al revés.

Las pantallas HTMX (`/app/applications/**` del candidato y `/app/jobs/{id}/pipeline` del reclutador) se construyen con el flujo completo de la Ola 1, no por endpoint.

## 9. Pruebas

- **Máquina de estados:** todos los pares (estado y etapa de origen, destino, iniciador); los válidos pasan y los demás fallan con `INVALID_TRANSITION`.
- **BR-TRACK-01 con Postgres real:** dos envíos simultáneos del mismo candidato tienen exactamente un ganador, repetido en varias rondas como la carrera de publicar en `job`; repostular tras retirarse funciona.
- **Idempotencia:** el mismo `Idempotency-Key` devuelve la misma postulación sin crear otra ni otra copia en `matching`.
- **Autorización:** cada caso de uso contra el candidato dueño, otro candidato, el reclutador dueño de la vacante, otro reclutador y una cuenta en periodo de gracia; lo ajeno es `404`.
- **Reglas de postular:** vacante en borrador, cerrada, pausada, eliminada y propia (BR-ACCT-05); candidato sin nombre, sin contacto o sin CV (BR-CAND-01).
- **TD-040:** postular y cambiar de etapa dejan la copia del perfil y la foto del puntaje; si la copia falla, no queda postulación.
- **Pipeline:** orden por puntaje con empates, filtros y paginación; un postulante sin vector todavía aparece como «puntaje pendiente», nunca con un número a medias.
- **Anonimización:** tras anonimizar al candidato la postulación sigue en el pipeline con el marcador y sin contacto, y su CV responde `410`.
- **Privacidad:** eventos y logs sin nombre, contacto ni nombre de archivo (BR-DATA-01).
- **Mutación (PIT):** sobre la máquina de estados, como en `job`.
- **TD-017:** `ats` y `tracker` suman controladores y contextos de prueba. Este bloque es el disparador de la deuda: las pruebas web nuevas reutilizan un contexto compartido en lugar de crear uno por clase.

## 10. Orden de entrega (Ola 1)

| Sub-paso | Entrega | Depende de |
|---|---|---|
| 0 | Subida y descarga del CV en `cv` (`checkapplicationreadiness` incluido). **Hecho:** la subida y la descarga llegaron con el bloque de archivos (ADR-0021); `checkapplicationreadiness` se construyó el 27-nov-2026 | ADR-0021 (aprobado) y sus verificaciones V1 a V3 |
| 1 | Migración de `applications` (A1, A3, A4) y `application_status_history` (A2); dominio con la máquina de estados y su prueba exhaustiva | 0 |
| 2 | `getapplicationgate` en `job`; `submitapplication` con idempotencia, copia en `matching` (TD-040) y su evento; API | 1 |
| 3 | `withdrawapplication`; `listcandidateapplications`; `tracker` con `listmyapplications` y `getmyapplication` | 2 |
| 4 | `getapplicantscores` en `matching` y `getapplicantprofiles` en `cv`; `listjobapplications` y `getapplication` | 2 |
| 5 | `changeapplicationstage`, `decideapplication` y `reopenapplication`; `presigncvdownload` y `getapplicationcv` | 4, 0 |
| 6 | Pantallas HTMX del candidato y del pipeline | 3, 5 |

## 11. Decisiones abiertas

| ID | Pregunta | Recomendación |
|---|---|---|
| **D1** | ¿El candidato ve la etapa interna (`INTERVIEW`, `OFFER`) o solo el `status`? | **Decidida el 27-nov-2026 por el responsable:** ve el estado y la etapa pública (las columnas del tablero kanban). Los estados internos que defina el reclutador dentro de una columna son solo suyos (§3.1, BR-TRACK-03) |
| **D2** | ¿Etapas fijas en la Ola 1 o configurables desde el principio? | **Decidida el 27-nov-2026 por el responsable:** fijas en la Ola 1 (`NEW → REVIEW → INTERVIEW → OFFER`, las columnas públicas de D1). La configuración de la Ola 2 (estados internos del reclutador, REQ-R-01) se decidirá con un análisis más profundo al empezar esa ola; lo escrito en §3.1 y §4.3 sobre ella es la dirección prevista, no un diseño cerrado |
| **D3** | ¿Un descarte o una contratación pueden reabrirse? ¿Puede repostular quien fue descartado? | **Decidida el 27-nov-2026 por el responsable:** el reclutador puede reabrir un descarte (por ejemplo, el elegido no aceptó y se vuelve a otro que ya pasó las pruebas, o a quien se descartó por no responder); vuelve a la etapa que tenía. El candidato descartado no puede repostular a esa vacante, solo vuelve si el reclutador lo reabre; sí puede postular a otras vacantes del mismo reclutador. Una contratación no se reabre (§3.1, BR-TRACK-04 y BR-TRACK-05) |
| **D4** | ¿Cuándo hace falta una proyección del puntaje en `ats` para ordenar el pipeline en SQL? | **Decidida el 27-nov-2026 por el responsable:** el pipeline ordena en memoria con el tope de §6 (regla 6), sin duplicar el puntaje en `ats`. La proyección solo se construye cuando una vacante supere ese tope o el pipeline medido pase de 1 s; ese disparador se registrará como deuda técnica al construir el pipeline (sub-paso 4) |
| **D5** | El catálogo de motivos de descarte (REQ-R-03 es de la Ola 2; se adelanta un catálogo mínimo porque un descarte sin motivo no se podría auditar después) | **Decidida el 27-nov-2026 por el responsable:** catálogo corto y cerrado (`NOT_A_FIT`, `POSITION_FILLED`, `CANDIDATE_UNRESPONSIVE`, `OTHER`), interno: el candidato ve «No continúa», sin el motivo (BR-TRACK-03). Al reabrir (D3) el motivo se quita de la postulación, pero queda en `application_status_history`, así que la auditoría no lo pierde. El catálogo completo de REQ-R-03 se revisa en la Ola 2 |
| **D6** | ¿El candidato recibe un aviso cuando cambia su estado? | **Decidida el 27-nov-2026 por el responsable:** sí, pero llega con las notificaciones (ADR-0022, sin escribir). En la Ola 1, BR-TRACK-02 se cumple al consultar el tracker. Los eventos `ApplicationStatusChanged` ya salen por la bandeja, así que las notificaciones los consumirán sin cambiar `ats`. Un cambio de estado interno (D1) no genera aviso |

## 12. No cubierto todavía

- Notificaciones por correo e in-app (ADR-0022).
- La generación del CV de marca desde el pipeline (Ola 3).
- Analítica del embudo (REQ-R-09).
- Organizaciones y equipos de reclutadores: hoy el dueño de la vacante es una cuenta (ADR-0024, sin escribir).
