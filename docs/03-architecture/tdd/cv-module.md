# TDD — `cv-module`
## Sistema Inteligente de Análisis de Compatibilidad CV-Vacante con CRM de Postulaciones

| Campo | Valor |
|---|---|
| **Estado** | 🟡 En revisión |
| **Versión** | 1.1 — huecos y decisiones abiertas resueltos, modelo de datos y reglas actualizados |
| **Fecha** | 23-nov-2026 |
| **Documentos relacionados** | ADR-0008 (perfil compartido), ADR-0013 (eliminación y anonimización), ADR-0016 (bandeja de salida), ADR-0015 (reglas ARCH), `domain-model.md` §3, §4 y §6, `data-model.md` §4.2 a §4.5, §4.10 y §4.12, `02-hld-system-design.md` §4.3, BR-ACCT-01 a 09, BR-CAND-01 a 07, BR-MATCH-10, BR-JOB-21, BR-DATA-01 a 06, BR-VIS-04 y BR-VIS-06 |

---

## 1. Qué cubre este documento

El *Technical Design Document* del módulo `cv` para el bloque «Perfil profesional + candidato» de la Ola 1 (~3 sprints, `roadmap-and-release-plan.md` §3). Las reglas ya están en `business-rules.md` y el modelo en `domain-model.md` y `data-model.md`; aquí se fija **cómo se construye**: qué casos de uso existen, dónde vive cada pieza, qué cambios exige al modelo de datos y en qué orden se entrega.

Según la organización por casos de uso de `coding-standards.md`, este documento es el que **nombra y crea las carpetas de casos de uso** de `cv`: hasta ahora el módulo solo tiene el esqueleto vacío, y no se inventan carpetas antes de que su TDD las nombre.

### 1.1 Qué entra y qué no

| Entra (Ola 1) | Fuera, con su dueño |
|---|---|
| `ProfessionalProfile` compartido: nombre, titular, biografía, experiencia, formación, certificaciones y proyectos (BR-ACCT-02, BR-ACCT-06) | `RecruiterProfile` (BR-ACCT-07, BR-ACCT-08): Ola 3 |
| `CandidateProfile`: contacto, habilidades, idiomas, autorizaciones de trabajo, atributos de elegibilidad, disponibilidad, descubribilidad (BR-CAND-04, BR-DATA-03) | Subida y almacén del archivo de CV, y la foto: ADR-0021 (aprobado el 26-nov-2026, bloque «Almacenamiento de archivos»); el análisis del texto del CV queda fuera |
| Lista de exclusión de disponibilidad (BR-CAND-05, BR-CAND-06) | Generación de CV propio y de marca (BR-CVGEN-01 a 03): Ola 3 |
| Contrato de anonimización de los datos de `cv` (BR-DATA-05) | Orquestación de la eliminación: periodo de gracia, lote programado y llamada a Clavaris (BR-DATA-04, BR-DATA-06). Va con el bloque «Cumplimiento y ciclo de vida de cuenta» (§9) |
| Superficie de lectura mínima para `matching` (§7) | Vistas de un candidato para un reclutador (pipeline, sugeridos): las diseñan los TDD de `ats` y `matching` sobre esa superficie |
| | Cálculo de embeddings y de la representación canónica: caso de uso `embedentity` de `matching` (`03-lld.md` §2.5) |

## 2. Reparto de responsabilidades

| Pieza | Dónde | Por qué allí |
|---|---|---|
| `AccountId`, `Actor`, `Capability` | `common`, `domain.model` | Ya existen y los usan todos los módulos; `cv` no depende de `auth` (ARCH-04) |
| Agregados, valores y reglas de los perfiles | `cv`, `domain` | Lógica de negocio sin Spring ni JPA (ARCH-01) |
| Casos de uso, `Command`/`Result` y puertos | `cv`, `application.usecase.<caso>` | Un caso de uso por carpeta; el límite transaccional es el caso de uso (ADR-0016) |
| Persistencia | `cv`, `infrastructure.adapter.out.persistence` | Adaptador JDBC como en `auth`; ninguna entidad JPA fuera de ahí (ARCH-07) |
| Controladores HTMX (`/app/profile/**`) y JSON (`/api/v1/me/**`) | `cv`, `infrastructure.adapter.in.web` | ARCH-08; ambos llaman al mismo caso de uso, nunca al adaptador de salida (ARCH-14) |
| Cableado de casos de uso como *beans* | `cv`, `infrastructure.config` | Sin estereotipos en `application/` (ARCH-02) |
| Sobre de error, límite de tasa, métrica de negocio, `traceId`, `accountId` | `app` y `common` | Ya existen desde el paso 5 del Sprint 0; `cv` los usa, no los reimplementa |

## 3. Modelo de dominio

Tres agregados independientes sobre una misma cuenta (`domain-model.md` §4). Cada uno se carga y se guarda entero, con una sola transacción por caso de uso.

| Agregado | Raíz y contenido | Invariantes principales |
|---|---|---|
| `ProfessionalProfile` | Raíz: `accountId` (1:1), nombre, titular, biografía. Hijas con identidad: `Experience`, `Education`, `Certification`, `Project` | Un perfil por cuenta (BR-ACCT-02); nombre no vacío; en cada hija con fechas, `inicio ≤ fin`, y `fin` ausente significa «en curso»; el nivel académico es uno de los seis valores del catálogo |
| `CandidateProfile` | Raíz: `accountId` (1:1), contacto, atributos de elegibilidad, `availabilityStatus`, `discoverable`. Conjuntos sin identidad propia: habilidades, idiomas, autorizaciones de trabajo | Solo existe si la cuenta tiene la capacidad de candidato (BR-ACCT-01); exige que exista el `ProfessionalProfile` de la misma cuenta; correo de contacto obligatorio (BR-CAND-01); expectativa salarial opcional, pero con moneda y periodo si se declara y `salaryMin ≤ salaryMax` (BR-CAND-07); sin duplicados por país, idioma o habilidad |
| `AvailabilityExclusion` | Entidad propia `(candidatoId, cuentaExcluida)` | Una vez por cuenta; el candidato no puede excluirse a sí mismo; sin restricción de rol de la cuenta excluida (BR-CAND-05) |

**Qué decide el dominio y qué no.** El dominio valida formas y rangos. Quién puede hacer qué (la capacidad del actor) lo comprueba el caso de uso con el `Actor`, nunca el controlador. Quién *ve* el estado de disponibilidad (BR-VIS-06) es una regla de lectura del consumidor, no de estos agregados (§7).

### 3.1 Valores cerrados

Las reglas de elegibilidad de `job` comparan contra estos valores (BR-ELIG-01), así que son **catálogos cerrados desde el primer día**; un texto libre las haría imposibles de evaluar. Los valores están aprobados y llevan su restricción `CHECK` en `data-model.md` v1.2.

| Campo | Valores | Dónde |
|---|---|---|
| `educationLevel` | `SECONDARY`, `TECHNICAL`, `BACHELOR`, `POSTGRAD_SPECIALIZATION`, `MASTERS`, `DOCTORATE` | §4.3 |
| `availabilityStatus` | `OPEN_TO_WORK`, `NOT_LOOKING`, `UNSET` (por defecto) | §4.4, BR-CAND-04 |
| `projectType` | `WORK`, `PERSONAL` (el modelo decía `LABORAL`; se unificó en inglés como el resto de catálogos, sin migración que corregir porque la tabla aún no existe) | §4.3 |
| `desiredModality` | `REMOTE`, `HYBRID`, `ONSITE` | §4.4 |
| `preferredWorkSchedule` | `FULL_TIME`, `PART_TIME`, `WEEKENDS` | §4.4 |
| `languageProficiency` | `A1`, `A2`, `B1`, `B2`, `C1`, `C2`, `NATIVE` | §4.12 |
| `salaryPeriod` | `MONTH`, `YEAR` | §4.4 |
| `skillProficiency` | 1 básico, 2 intermedio, 3 avanzado, 4 experto | §4.12 |
| `skillSource` | `DECLARED`, `PARSED_CONFIRMED` | §4.12 |
| `country`, `language`, `currency` | ISO 3166-1 alfa-2, ISO 639-1 e ISO 4217, validados en la aplicación contra la lista del JDK, **sin tabla de catálogo** | `data-model.md` §6 |

## 4. Casos de uso del módulo

Carpetas de `cv-module/src/main/java/com/usac/jobseeker/cv/application/usecase/`. Todas son operaciones del titular sobre **su propia** cuenta: el identificador viene del `Actor`, nunca de la ruta ni del cuerpo, así que no hay forma de editar el perfil de otro.

| Carpeta | Qué hace | Capacidad exigida | Evento que emite |
|---|---|---|---|
| `getmyprofessionalprofile` | Devuelve el perfil profesional completo; si aún no existe, un estado «sin crear», no un error | cualquiera | — |
| `saveprofessionalprofile` | Crea el perfil la primera vez (BR-ACCT-02, BR-ACCT-09) o actualiza nombre, titular y biografía. Idempotente | cualquiera | `ProfileUpdated` |
| `manageexperiences` | Añade, modifica o elimina una experiencia | cualquiera | `ProfileUpdated` |
| `manageeducation` | Idem para formación | cualquiera | `ProfileUpdated` |
| `managecertifications` | Idem para certificaciones | cualquiera | `ProfileUpdated` |
| `manageprojects` | Idem para proyectos | cualquiera | `ProfileUpdated` |
| `getmycandidateprofile` | Devuelve el perfil de candidato, o «sin crear» | candidato | — |
| `savecandidateprofile` | Crea el perfil de candidato o actualiza contacto y atributos de elegibilidad (país de residencia, modalidad, jornada, reubicación, expectativa salarial) | candidato | `ProfileUpdated` |
| `replacecandidateskills` | Reemplaza el conjunto de habilidades | candidato | `ProfileUpdated` |
| `replacecandidatelanguages` | Reemplaza el conjunto de idiomas con su nivel | candidato | `ProfileUpdated` |
| `replaceworkauthorizations` | Reemplaza los países con autorización de trabajo | candidato | `ProfileUpdated` |
| `changediscoverability` | Activa o desactiva la descubribilidad (BR-DATA-03) | candidato | `CandidateDiscoverabilityChanged` |
| `changeavailability` | Declara el estado de disponibilidad (BR-CAND-04) | candidato | `CandidateAvailabilityChanged` |
| `manageavailabilityexclusions` | Lista, añade y quita cuentas de la lista de exclusión (BR-CAND-05) | candidato | `CandidateAvailabilityExclusionChanged` |
| `getprofilesnapshot` | Lectura para otros módulos, sin datos de contacto (§7) | interna | — |
| `getprofilesnapshots` | La misma lectura para **varias cuentas con pocas consultas** (hasta 500): la individual es el caso de una, con las mismas reglas de capacidad y anonimización (TD-039) | interna | — |
| `getvisibleavailabilities` | La disponibilidad que un reclutador puede ver de **varios** candidatos, con la misma regla de exclusión que `getvisibleavailability` (BR-CAND-05, BR-VIS-06; TD-039) | interna | — |
| `anonymizeprofiledata` | Anonimiza los datos de `cv` de una cuenta (§9) | interna (lote) | `AccountAnonymized` |
| `uploadcv` | Sube una versión nueva del CV: valida tamaño y tipo real, escanea, guarda y la marca vigente (ADR-0021, §10.7) | candidato | — |
| `getmycv` | El CV vigente y las versiones anteriores, sin URL | candidato | — |
| `downloadmycv` | URL firmada de 5 minutos de una versión propia (ADR-0021 §3) | candidato | — |
| `reconcileorphancvfiles` | Borra del almacén los CV sin versión que los referencie, con más de 24 h y con un freno ante muchos de golpe (TD-041) | interna (planificada) | — |
| `uploadprofilephoto` | **Hecho (§10.8).** Subir la foto de perfil, re-codificada sin metadatos EXIF; `getmyprofilephoto` la sirve y `removeprofilephoto` la quita | cuenta | — |
| `checkapplicationreadiness` | **Planificado para `ats`.** Qué le falta a la cuenta para postular (BR-CAND-01: nombre, contacto y CV) y el CV vigente (`tdd/ats-tracker-modules.md` §5) | interna | — |
| `getapplicantprofiles` | **Planificado para `ats`.** Por lotes, nombre, titular y contacto de postulantes; solo tras autorizar al reclutador en `ats` (REQ-C-07) | interna | — |
| `presigncvdownload` | **Planificado para `ats`.** URL firmada de una versión concreta del CV; autoriza quien la pide (ADR-0021 §3) | interna | — |

**Por qué estas granularidades.** Las colecciones con identidad propia (experiencias, formación…) se editan elemento a elemento, porque la interfaz las edita así y cada elemento tiene su historial. Los conjuntos sin identidad (habilidades, idiomas, autorizaciones) se **reemplazan enteros**: la operación es idempotente por naturaleza y evita reconciliar altas y bajas. Descubribilidad y disponibilidad llevan casos de uso aparte porque sus eventos son distintos y **no** disparan lo mismo: la descubribilidad saca al candidato de los sugeridos de inmediato, y la disponibilidad nunca recalcula un score (`domain-model.md` §6).

**Qué no se crea.** No hay un caso de uso «habilitar la capacidad de candidato»: la capacidad es de `auth` (BR-ACCT-09) y el perfil se crea cuando se usa por primera vez, no al registrarse.

## 5. Decisiones de diseño

1. **El perfil nace al primer guardado, no al registrarse** (BR-ACCT-09). Hasta entonces la lectura devuelve «sin crear» y la interfaz muestra el formulario vacío. Así no hay perfiles que nadie pidió.
2. **El nombre se pide en el primer guardado.** `accounts` no guarda datos personales (corte con Clavaris, `data-model.md` §4.1) y `full_name` es obligatorio, así que el primer guardado de `saveprofessionalprofile` exige nombre. **Clavaris no lo entrega** (sin claim `name`, hallazgo G4 del spike `06-spike-contrato-clavaris.md`), así que la pantalla lo pide vacío: no hay nada que precargar (D2).
3. **Cada operación sobre un agregado es una transacción y una versión.** Los agregados llevan un contador de versión (`@Version` en el sentido de bloqueo optimista, `03-lld.md` §8): dos pestañas editando a la vez no se pisan en silencio, la segunda recibe `409 STALE_STATE`. Editar un elemento hijo incrementa la versión de la raíz. Exige una columna nueva (§8).
4. **Los eventos salen por la bandeja, en la misma transacción** (ADR-0016): a lo sumo una vez cada vez que se confirma un cambio, con consumidores idempotentes, y con **solo identificadores** en la carga (`accountId`, tipo de cambio, versión). Nunca nombre, contacto ni texto de perfil (BR-DATA-01).
5. **`ProfileUpdated` solo se emite si la cuenta tiene la capacidad de candidato** (`domain-model.md` §6 y la pregunta abierta de `data-model.md` §6). Un reclutador que edita su experiencia no dispara recálculos que nadie va a leer. El caso de uso consulta la capacidad del `Actor`.
6. **No se agrupan ráfagas de eventos en el emisor.** Diez ediciones seguidas emiten diez eventos; quien los consume ya está protegido por el *hash* del contenido, que evita llamar a Ollama si la representación no cambió (BR-MATCH-03, `data-model.md` §4.4). Agrupar aquí añadiría un temporizador sin ganancia medida.
7. **Ningún dato del perfil entra al log ni a una etiqueta de métrica** (BR-DATA-01). Los registros llevan el nombre del caso de uso, el resultado y el `traceId`; el `accountId` ya lo añade el filtro de actor.
8. **Deshabilitar la capacidad de candidato conserva la fila.** El perfil se conserva con la capacidad apagada y deja de ser descubrible y de aparecer en listados; reactivarla lo recupera. Resuelve la pregunta de `data-model.md` §6 (D5). Que deje de ser descubrible al apagar la capacidad lo hará cuando exista ese caso de uso de `auth` (§12, D5).

## 6. API

Rutas de ejemplo; el contrato lo genera springdoc de los controladores anotados (ADR-0010) y se valida en CI. Todas heredan las convenciones de `api-contract-overview.md`: actualización con `PUT`/`PATCH`, `DELETE` para borrar, sobre de error estándar.

| Método y ruta | Caso de uso | Rate limit | Idempotente | Errores clave |
|---|---|---|---|---|
| `GET /api/v1/me/professional-profile` | `getmyprofessionalprofile` | lectura | sí | 401 |
| `PUT /api/v1/me/professional-profile` | `saveprofessionalprofile` | escritura | sí | 400 `VALIDATION_FAILED`, 409 `STALE_STATE` |
| `POST`/`PUT`/`DELETE /api/v1/me/professional-profile/experiences[/{id}]` (y `education`, `certifications`, `projects`) | `manage…` | escritura | `PUT` y `DELETE` sí | 404 `NOT_FOUND`, 400, 409 |
| `GET`/`PUT /api/v1/me/candidate-profile` | `getmycandidateprofile`, `savecandidateprofile` | lectura / escritura | `PUT` sí | 403 `FORBIDDEN_CAPABILITY`, 400, 409 |
| `PUT /api/v1/me/candidate-profile/skills`, `…/languages`, `…/work-authorizations` | `replace…` | escritura | sí | 403, 400 |
| `PUT /api/v1/me/candidate-profile/discoverability`, `…/availability` | `change…` | escritura | sí | 403 |
| `GET`/`POST`/`DELETE /api/v1/me/candidate-profile/availability-exclusions[/{accountId}]` | `manageavailabilityexclusions` | lectura / escritura | `POST` por clave natural | 403, 404, 409 duplicada |
| `POST /api/v1/me/candidate-profile/cv` (multipart, campo `file`) | `uploadcv` | `upload` (10/h) | el mismo archivo no crea otra versión | 403, 404 sin perfil, 422 `FILE_REJECTED`, 503 `UPSTREAM_UNAVAILABLE` |
| `GET /api/v1/me/candidate-profile/cv` | `getmycv` | lectura | sí | 403, 404 |
| `GET /api/v1/me/candidate-profile/cv/{fileId}/download` | `downloadmycv` | lectura | sí | `302` a la URL firmada; 403, 404, 503 |

Las pantallas Thymeleaf + HTMX viven en `/app/profile/**` y devuelven fragmentos; toda petición mutante lleva el token CSRF y entra en la prueba que recorre las rutas mutantes (definición de hecho, punto 16). Una cuenta sin capacidad de candidato recibe `403` en cualquier ruta de candidato; la cuenta en `PENDING_DELETION` ni siquiera llega a ellas, porque `resolveactor` la rechaza (BR-DATA-04).

## 7. Superficie hacia otros módulos

Lo único que otro módulo puede usar de `cv` son las interfaces de sus casos de uso con sus `Command`/`Result`, y sus eventos (ARCH-04). El módulo consumidor la usa **desde su adaptador de salida**, que traduce a su propia proyección; así `matching` nunca ve una entidad de `cv` (`03-lld.md` §2.2, capa anticorrupción).

- **`getprofilesnapshot`** devuelve datos estructurados y de elegibilidad: titular, experiencia, formación (nivel y campo, **sin** nombre de institución), habilidades, idiomas, país, modalidad, jornada, reubicación y expectativa salarial. **No** devuelve nombre, foto, correo, teléfono ni enlaces. La minimización de `02-hld-system-design.md` §4.3 queda así garantizada **en el origen**, no confiada a cada consumidor. Con ese snapshot `matching` construye la representación canónica, porque esa forma depende del modelo de embeddings (ADR-0018) y no es una preocupación de `cv`. Esto responde la pregunta de `domain-model.md` §8 sobre `raw_cv_text` frente a los datos estructurados: **el embedding se calcula de los estructurados**.
- **Eventos** (`domain.event`): `ProfileUpdated`, `CandidateDiscoverabilityChanged`, `CandidateAvailabilityChanged`, `CandidateAvailabilityExclusionChanged` y `AccountAnonymized`. Solo identificadores.
- **Quién ve el estado de disponibilidad (BR-VIS-06)** lo decide el consumidor: `cv` expone la lista de exclusión por una consulta (`manageavailabilityexclusions` para el titular y una consulta interna «¿esta cuenta está excluida por este candidato?») y el módulo que muestra el estado aplica el filtro. `cv` no sabe quién mira.
- **Lecturas de `job`** (atributos de elegibilidad para `validatejobeligibility`): se diseñan con el TDD de `job` sobre `getprofilesnapshot`, sin tocar la tabla.

## 8. Cambios al modelo de datos

Al contrastar `data-model.md` con este diseño aparecieron huecos. Todos están **resueltos e incorporados a `data-model.md` v1.2**; ninguna migración existe todavía (la única migración de `cv` que hay en el repositorio es la del anexo del spike R13, que no se ejecuta). Las migraciones siguen el ADR-0027 y se escriben con el sub-paso que las usa (§10).

| # | Hueco encontrado | Resolución | Dónde |
|---|---|---|---|
| M1 | Sin **moneda** ni **periodo** en el salario del candidato ni de la vacante | `salary_currency char(3)` y `salary_period` (`MONTH`/`YEAR`) en `candidate_profiles` y `job_postings`, «todo o nada» con los importes. El diseño ya estaba en `03-lld.md` §4 y nunca llegó al modelo. Reglas nuevas **BR-CAND-07**, **BR-JOB-21** y **BR-MATCH-10** (solo misma moneda, sin conversión) | `data-model.md` §4.4, §4.6 |
| M2 | `candidate_skills` solo en el diagrama | Diccionario completo: `raw_name`, `skill_id` (sin FK hasta el ADR-0019), nivel 1 a 4, origen, único por nombre normalizado | §4.12 |
| M3 | Sin columna de versión | `version bigint` en `professional_profiles` y `candidate_profiles` | §4.2, §4.4 |
| M4 | `contact_email NOT NULL` | Coherente con crear el perfil al primer guardado; sin cambio | §4.4 |
| M5 | `updated_at` descrito como disparador de recálculo | Aclarado: el disparo es el evento por la bandeja, condicionado a la capacidad de candidato | §4.2, §4.4 |
| M6 | Catálogos sin cerrar | `CHECK` con los valores de §3.1 | §4.3, §4.4, §4.12 |
| M7 | Qué hacer con la lista de exclusión al anonimizar | Se eliminan las filas del candidato anonimizado; las de otros candidatos que lo excluían no se tocan | §4.10 |
| M8 | El CV cargado no tenía tabla (solo `raw_cv_text`, que nada rellena) | `candidate_cv_files`, una fila por versión inmutable (ADR-0021 §5), §10.7 | §4.4.1 |

Las pruebas de la migración (la de «publicadas inmutables» y el análisis de `squawk`) existen desde el paso 2 del Sprint 0 y cubren estas migraciones sin trabajo extra. Las restricciones nuevas sobre tablas con filas se añaden `NOT VALID` y se validan después; en tablas nuevas, como estas, no hace falta.

## 9. Anonimización (BR-DATA-05)

`anonymizeprofiledata` recibe una cuenta y anonimiza **solo los datos de `cv`**, como dice `data-model.md` §4.2 y §4.4: nombre al marcador genérico, titular, biografía y foto a nulo; contacto y texto de CV a nulo; el vector del perfil **no** está en `cv` sino en `matching`, que lo anula al recibir `AccountAnonymized` (`data-model.md` §4.7.1); `discoverable` forzado a `false`; las filas hijas y las autorizaciones e idiomas se eliminan físicamente; el resto de atributos de preferencia se conserva porque no identifica a nadie por sí solo. La lista de exclusión del candidato anonimizado se elimina (`data-model.md` §4.10).

**Idempotente y reanudable** (`03-lld.md` §8): cada paso comprueba su estado y un reintento tras una caída a mitad termina el trabajo, nunca lo repite con otro resultado. Emite `AccountAnonymized`, que invalida cualquier score en caché con datos derivados.

**Lo que este documento no decide:** qué módulo es dueño del lote que recorre las cuentas vencidas y llama a Clavaris (BR-DATA-06). `domain-model.md` §8 lo deja abierto y ADR-0013 describe el lote sobre los datos de `cv`; se resuelve en el TDD del bloque «Cumplimiento y ciclo de vida de cuenta». Este módulo solo promete el contrato anterior, que ese lote invoca por cuenta.

## 10. Orden de entrega

| Sub-paso | Entrega | Depende de |
|---|---|---|
| 1 | ✅ **Hecho (23-nov-2026).** Migración de `professional_profiles` (solo la raíz), `ProfessionalProfile` con sus reglas, `getmyprofessionalprofile` y `saveprofessionalprofile`, adaptador JDBC y el controlador JSON. **Es la rebanada plantilla del módulo**: lleva todas las transversales. Detalle y desvíos de este documento en §10.1 | — |
| 2 | ✅ **Hecho (24-nov-2026).** Las tablas hijas, las cuatro colecciones (`manage*`) y **las pantallas HTMX del perfil**. Detalle y desvíos de este documento en §10.2 | 1 |
| 3 | ✅ **Hecho (23-nov-2026).** `CandidateProfile`: moneda y periodo (M1), `get`/`save`, habilidades, idiomas, autorizaciones, su API JSON y su pantalla `/app/candidate-profile`. Detalle y desvíos de este documento en §10.3 | 1 |
| 4 | ✅ **Hecho (24-nov-2026).** Descubribilidad, disponibilidad y lista de exclusión, con sus eventos, su API JSON y su pantalla `/app/candidate-visibility`. Detalle y desvíos de este documento en §10.4 | 3 |
| 5 | ✅ **Hecho (24-nov-2026).** `getprofilesnapshot`, el catálogo de eventos de `cv` y sus pruebas de contrato (forma del resultado, eventos por la bandeja real). Detalle y desvíos de este documento en §10.5 | 3, TDD de `matching` |
| 6 | ✅ **Hecho (24-nov-2026).** `anonymizeprofiledata` con su migración, el evento `profile.anonymized` y sus pruebas. Detalle y desvíos de este documento en §10.6 | 2, 3, 4 |
| 7 | ✅ **Hecho (26-nov-2026).** El CV (ADR-0021): `candidate_cv_files` (M8), `uploadcv`, `getmycv`, `downloadmycv`, su API y el borrado físico al anonimizar. Diseño y desvíos en §10.7 | 3, 6, ADR-0021 |
| 8 | ✅ **Hecho (26-nov-2026).** La foto de perfil (`uploadprofilephoto`, `getmyprofilephoto`, `removeprofilephoto`), su API, su sección en `/app/profile` y la pantalla del CV `/app/candidate-cv`. Diseño y desvíos en §10.8 | 2, 7 |

### 10.1 Sub-paso 1: qué se construyó y en qué se desvió de este documento

Se construyó lo descrito en §4 para `getmyprofessionalprofile` y `saveprofessionalprofile`, con estas decisiones que la primera versión de este documento no tenía:

- **Solo la tabla raíz.** La migración crea `professional_profiles`; las hijas llegan con el sub-paso 2, junto con los casos de uso que las escriben. Una tabla que nadie puede llenar no se puede probar de verdad. Se usa `text` con restricciones de longitud en vez de `varchar(n)` (cambiar un límite es más barato) y la clave foránea a `accounts` se prueba contra la migración real de `auth`, que `cv` tiene en su classpath por depender de ella.
- **Solo la API JSON; las pantallas HTMX pasan al sub-paso 2.** Una pantalla del perfil con solo nombre, titular y biografía habría que rehacerla al añadir las colecciones; se construye una vez con el perfil completo.
- **Validación es `400 VALIDATION_FAILED`, no `422`.** §6 decía 422, pero `api-contract-overview.md` §6 fija 400 para la validación. Se corrigió §6 y el sobre de error ganó un campo opcional `details` con `{field, code}` por violación, nunca el valor.
- **Dos errores nuevos y compartidos en `common`**: `ValidationFailedException` y `StaleStateException`. Los módulos los lanzan sin saber de HTTP y `ApiExceptionHandler`, en `app`, los traduce (400 y 409 `STALE_STATE`). El resto de módulos los reutilizará.
- **Idempotencia real.** Guardar datos **idénticos** a los vigentes, con la versión vigente, no cambia nada: no sube la versión, no emite evento y cuenta como `unchanged`. Un reenvío con una versión ya vieja sigue siendo `409`: sin una clave de idempotencia no se puede distinguir de una edición ajena, y esa clave es trabajo futuro.
- **Versión inicial 1.** El cliente envía `version: 0` para crear; el perfil guardado empieza en 1. La API responde `201` al crear y `200` al editar.
- **La capacidad se pregunta a `auth` por su caso de uso** (`ResolveActorUseCase`) desde un adaptador de salida; `cv` pasó a depender de `auth` en el pom, sin ciclo.
- **El evento lleva el `traceId`** de la petición (leído del `MDC` de SLF4J) y solo se emite si la cuenta tiene la capacidad de candidato.
- **Se valida el carácter NUL**, que PostgreSQL no admite en un `text`: sin esa regla llegaría como un `500` en vez de un `400`.

### 10.2 Sub-paso 2: qué se construyó y en qué se desvió de este documento

Se construyó lo descrito en §4 para `manageexperiences`, `manageeducation`, `managecertifications` y `manageprojects`, con la migración de sus cuatro tablas, la API JSON (§6) y la pantalla `/app/profile`. Decisiones que la primera versión de este documento no tenía:

- **Un coordinador común, `ProfileChange`.** Las cuatro colecciones comparten una regla que debe ser idéntica: comprobar la versión del perfil, avanzarla, escribir el elemento, publicar el evento, contar el resultado y devolver el perfil completo. Se escribe una vez y los cuatro servicios quedan en lo propio de cada colección (construir y validar el elemento). Una sola batería de pruebas, parametrizada, recorre las cuatro para que una diferencia entre ellas no pase desapercibida.
- **Editar un elemento avanza la versión del perfil**, como dice la decisión 3, y se avanza **antes** de escribir el elemento: esa escritura toma el bloqueo de la fila y, si otra edición ya la avanzó, falla sin tocar nada. Si el elemento no existe, la transacción revierte también el avance; la prueba de extremo a extremo lo comprueba contra Postgres, porque un doble en memoria no puede.
- **Toda operación devuelve el perfil completo** con su versión nueva, así el cliente nunca queda con una versión vieja para la edición siguiente. El borrado envía la versión como parámetro de consulta (`?version=`), porque un `DELETE` con cuerpo no es fiable.
- **Un elemento ajeno responde igual que uno inexistente: 404.** Para eso se añadió `NotFoundException` a `common`, con su traducción a `RESOURCE_NOT_FOUND`: confirmar que un identificador ajeno es válido ya sería una fuga (BR-ACCT-04). Un perfil aún sin crear tampoco tiene nada que editar y responde 404.
- **Máximo de 50 elementos por colección** (`TOO_MANY`, 400), que el diseño no fijaba: acota el abuso y el tamaño de la respuesta.
- **Reglas de los datos.** Las fechas deben estar entre 1900 y 2100 (atrapa años mal tecleados) y el fin no puede ser anterior al inicio. Los enlaces solo pueden ser `http` o `https` con servidor: `javascript:`, `data:`, `ftp:` y los relativos se rechazan, porque el perfil los muestra como un enlace en el que otra persona pulsa. La base de datos repite estas reglas con restricciones `CHECK`: es la última defensa, no la única.
- **El nivel académico muestra etiquetas en español** (`Licenciatura o grado`), no el código interno que usan la API y las reglas de elegibilidad.
- **Pantalla HTMX.** Una operación devuelve el fragmento con el perfil completo, que sustituye al anterior. Un error de validación responde **422 con solo la zona de errores**, para que la persona no pierda lo que acaba de teclear; una versión vieja (409) o un elemento que ya no existe (404) recargan todo el contenido con un aviso. La página cumple la política de seguridad de contenido sin estilos ni scripts en línea, y lo que la persona escribe se escapa. Las trece rutas que mutan quedan cubiertas por la prueba que recorre todas las de `/app/**` exigiendo el token CSRF, sin tocarla.

**Pendiente de este sub-paso, no implementado:** el orden manual de los elementos (hoy se ordenan por fecha, lo vigente primero) y una vista pública del perfil para otro usuario, que dependen de los TDD de `ats` y de `matching`.

### 10.3 Sub-paso 3: qué se construyó y en qué se desvió de este documento

Se construyó el perfil de candidato: contacto y datos de elegibilidad, y los tres conjuntos (habilidades, idiomas, autorizaciones de trabajo), con su migración, la API JSON y la pantalla `/app/candidate-profile`. Decisiones que la primera versión de este documento no tenía:

- **Una versión propia del perfil de candidato**, independiente de la del perfil profesional. Guardar los datos y reemplazar cualquiera de los tres conjuntos avanzan **esa** versión, que viaja en toda escritura; el `409 STALE_STATE` y el «guardar lo mismo no cambia nada» funcionan igual que en el sub-paso 1. Un coordinador común, `CandidateChange`, hace para los tres conjuntos lo que `ProfileChange` hace para las colecciones.
- **Los conjuntos se reemplazan enteros** (`PUT .../skills`, `.../languages`, `.../work-authorizations`), no elemento a elemento: no tienen identidad propia y la persona los edita como una lista. Reemplazar con una lista vacía lo vacía; omitir el campo es un `400`, para que un olvido no borre datos. Reemplazar por un conjunto igual al guardado no sube la versión ni emite evento.
- **Un duplicado es un error, no se descarta en silencio**: «Java» y « java » son la misma habilidad y se rechaza con `DUPLICATE` apuntando al elemento (`skills[1].name`); lo mismo para idiomas y países. Límites: 100 habilidades, 30 idiomas, 50 países.
- **La habilidad es texto libre con nivel opcional de 1 a 4**, sin enlace a la taxonomía: el ADR-0019 ya admite una habilidad sin enlace y el enlazado llega con `matching`. La tabla `candidate_skills` entra a la lista de `cv` en el ADR-0027.
- **El salario es todo o nada** (BR-CAND-07): con cualquier dato hacen falta moneda ISO 4217 y periodo; el rango no puede estar invertido. Los importes se guardan con **escala fija de dos decimales**: sin ella, `4500` y `4500.00` serían datos distintos y un reenvío idéntico subiría la versión. La prueba contra Postgres lo detectó.
- **La capacidad de candidato se exige antes de validar los datos** (BR-ACCT-04) y su ausencia es `403 FORBIDDEN_CAPABILITY`, un error nuevo y compartido en `common` que no dice qué capacidad falta. Crear el perfil de candidato sin perfil profesional es `400` sobre `professionalProfile`.
- **Descubribilidad y disponibilidad no se editan aquí**: nacen en `true` y `UNSET` y ninguna escritura de este sub-paso las toca (la prueba del adaptador lo verifica); son del sub-paso 4.
- **Pantalla.** Una línea por elemento en un cuadro de texto (`Java;4`, `en;B2`, `GT, MX`), con el error apuntando a la línea. Mismo comportamiento HTMX que el perfil profesional (422 solo con la zona de errores, 409 y 404 recargan).
- **Un hallazgo de la prueba contra Postgres**: un nivel de habilidad ausente se leía como 0 porque `wasNull()` se consultaba tras otra lectura que lo reinicia. Un doble en memoria no lo habría visto.

### 10.4 Sub-paso 4: qué se construyó y en qué se desvió de este documento

Se construyeron `changediscoverability`, `changeavailability` y `manageavailabilityexclusions`, la tabla `candidate_availability_exclusions`, la API JSON (§6), la pantalla `/app/candidate-visibility` y una consulta interna que §7 mencionaba sin nombrarla. Decisiones que la primera versión de este documento no tenía:

- **Descubribilidad y disponibilidad avanzan la versión del perfil de candidato** y llevan la versión esperada, igual que los demás cambios del perfil: un cambio de descubribilidad hecho con una pestaña vieja no debe pisar en silencio otro hecho en otra. Cada una escribe **solo su columna** (el `UPDATE` no toca los datos), así que no puede deshacer un guardado de contacto concurrente. Pedir el valor que ya tiene no cambia nada ni emite evento. `CandidateChange` ganó `editProfile`, que hace para estas dos columnas lo que `apply` hace para los conjuntos.
- **La lista de exclusión no lleva versión ni avanza la del perfil.** No son datos del perfil, y su clave natural (la cuenta) ya es el control de concurrencia: de dos altas simultáneas de la misma cuenta gana una (`201`) y la otra recibe `409 ALREADY_EXISTS`, un error nuevo y compartido en `common`, distinto de `STALE_STATE` porque reintentar con otro cuerpo no ayuda. Quitar una cuenta que no estaba es `404`.
- **Reglas de la lista:** no se puede excluir la propia cuenta (`400 SELF_EXCLUSION`; una `CHECK` no compara columnas de dos tablas, así que la regla es del dominio), la cuenta debe estar **viva** (`404` si no) y hay un tope de 500 cuentas que el diseño no fijaba.
- **«Viva» es lo que una persona puede volver a usar.** Una cuenta activa o en el periodo de gracia de un borrado (que su titular puede cancelar al volver, BR-DATA-04) se puede excluir. Una nunca aprovisionada, anonimizada o sin identidad no vuelve, y excluirla solo acumularía filas sin efecto: se responde igual que una inexistente. `cv` se lo pregunta a `auth` por un adaptador, sin leer `accounts`.
- **El `404` no es un oráculo útil.** Se mantiene la comprobación en vez de aceptar cualquier identificador porque un identificador mal copiado se guardaría sin avisar y la persona creería estar protegida. El riesgo de que revele si una cuenta existe es despreciable: son UUID aleatorios de 122 bits (el `sub` de Clavaris), no enumerables, y la ruta está bajo el límite de tasa de escritura. Para que no sirva ni como señal de estado, «nunca existió» y «ya no vuelve» dan la misma respuesta, byte a byte salvo el `traceId`, y el mensaje no incluye el identificador.
- **Los eventos llevan solo identificadores y la versión.** `CandidateDiscoverabilityChanged` y `CandidateAvailabilityChanged` **no** llevan el valor nuevo: quien los consume lee el dato por la superficie de lectura, como con `ProfileUpdated`. `CandidateAvailabilityExclusionChanged` lleva las dos cuentas y `added` o `removed`, nunca el estado de disponibilidad. Como la lista no avanza la versión, su evento lleva la versión vigente del perfil.
- **Consulta interna `getvisibleavailability`.** Responde «¿qué estado de este candidato puede ver esta cuenta?» y concentra BR-CAND-05 y BR-VIS-06 en un solo lugar: una cuenta excluida ve `UNSET` (no un error, para que no pueda notar que está excluida), el propio candidato ve el real, y quien no tiene perfil de candidato aparece como `UNSET`. Es más que la consulta «¿está excluida?» de §7, pero evita que `matching` y `ats` reimplementen la regla y puedan olvidarla. El resto del perfil no se ve afectado (BR-CAND-06).
- **Pantalla aparte** (`/app/candidate-visibility`, enlazada desde el inicio y desde el perfil de candidato) en lugar de otra sección de la misma página: así un cambio de visibilidad no recarga los formularios de datos, y cada controlador conserva pocas dependencias. Mismo comportamiento HTMX que las demás pantallas.

**Pendiente de este sub-paso, no implementado:** que `matching` consuma los tres eventos (actualizar su copia de `discoverable`, ordenar sugeridos por disponibilidad y aplicar BR-MATCH-09), que corresponde a su propio TDD, y desactivar la descubribilidad al apagar la capacidad de candidato (D5), que espera el caso de uso de `auth`.

### 10.5 Sub-paso 5: qué se construyó y en qué se desvió de este documento

Se construyó `getprofilesnapshot` con su resultado `ProfileSnapshot`, y se fijó con pruebas el contrato de los eventos de `cv` por la bandeja. Decisiones que la primera versión de este documento no tenía:

- **Qué sale y qué no.** Salen titular, experiencias (cargo, fechas, descripción), formación (título, **nivel de catálogo**, fecha), certificaciones (nombre y fechas), proyectos (nombre, descripción, tipo, rol, fechas), habilidades, idiomas, autorizaciones, país, modalidad, jornada, reubicación y salario con moneda y periodo, más `discoverable` y las versiones. **No** salen nombre, biografía, foto, correo, teléfono, enlaces, **ni el nombre de empresas, instituciones o emisores**: §7 solo excluía la institución, pero un nombre de empleador o emisor no aporta al cálculo y sí introduce sesgo de prestigio (ADR-0025 por escribir), así que se excluyen todos. El estado de disponibilidad tampoco sale: quién puede verlo depende de quién pregunta y eso lo resuelve `getvisibleavailability`.
- **Los valores de catálogo viajan como su nombre** (`"BACHELOR"`, `"HYBRID"`), no como el tipo de `cv`: ningún tipo interno cruza el límite del módulo, y `matching` traduce a los suyos en su adaptador.
- **Cada campo se copia a mano.** No se reutiliza ninguna vista existente, que podría crecer con un dato personal sin que nadie lo note: lo que no se copia en `GetProfileSnapshotService` no puede llegar a un consumidor.
- **«Sin perfil» no es un error.** Devuelve vacío si la cuenta no tiene la capacidad de candidato (el perfil se conserva pero deja de ser legible desde fuera, decisión 8) o si falta el perfil profesional o el de candidato. Se lee todo en una sola transacción de solo lectura para que ambos perfiles sean de la misma versión.
- **Las habilidades no llevan `skill_id`** todavía: la taxonomía (ADR-0019) la añade el sub-paso del enlazado. `matching` ya admite una habilidad en texto libre.
- **El texto libre no se redacta aquí.** Las descripciones pueden contener lo que la persona escribió (un correo, un teléfono); redactarlo antes de generar un embedding es de `matching` (ADR-0017, control sobre PII), porque `cv` no sabe para qué se usará.
- **Catálogo de eventos.** `cv` publica cuatro tipos por la bandeja, todos con `eventVersion` 1 y una carga con solo identificadores:

  | Tipo | Agregado | Carga | Cuándo |
  |---|---|---|---|
  | `profile.updated` | `professional_profile` o `candidate_profile` | `accountId`, `change`, `version` | cambia el perfil profesional (solo con la capacidad de candidato) o un dato o conjunto del de candidato |
  | `candidate.discoverability_changed` | `candidate_profile` | `accountId`, `version` | cambia la descubribilidad |
  | `candidate.availability_changed` | `candidate_profile` | `accountId`, `version` | cambia la disponibilidad |
  | `candidate.availability_exclusion_changed` | `candidate_profile` | `accountId`, `excludedAccountId`, `action` | se añade o quita una exclusión |

  Ninguna carga lleva el valor nuevo: quien consume lee `getprofilesnapshot` (o `getvisibleavailability`). La versión del agregado no retrocede, así que un consumidor puede descartar un evento más viejo que el que ya aplicó («el evento posterior gana»).
- **Prueba de contrato por la bandeja real:** un consumidor de prueba recibe, por el sondeador de verdad y sobre Postgres, los cuatro tipos que produce un perfil completo, con la forma acordada y sin ningún dato personal en la carga, y las filas quedan `PROCESSED`.

**Pendiente de este sub-paso, no implementado:** el adaptador de lectura de `matching` (`infrastructure.adapter.out.cv`) que traduce este resultado a `CandidateSignals` y a la representación canónica, y su prueba de que ningún dato personal llega a su dominio. Es el paso 3 del orden de entrega del TDD de `matching`; esta PR deja de `cv` todo lo que ese paso necesita. El evento `AccountAnonymized` llega con el sub-paso 6.

### 10.6 Sub-paso 6: qué se construyó y en qué se desvió de este documento

Se construyó `anonymizeprofiledata` (§9) con la migración `V20261124110000__cv_add_anonymization_state.sql`, el evento `profile.anonymized` y su adaptador. Decisiones que la primera versión de este documento no tenía:

- **Una columna `anonymized_at` en cada perfil.** §9 pedía «idempotente y reanudable» sin decir cómo se sabe el estado. Una marca explícita es más fiable que comparar el nombre con el marcador y hace que cada `UPDATE` lleve `anonymized_at is null`: la segunda ejecución no encuentra fila, no borra nada y no emite evento. Como todo ocurre en la transacción del caso de uso, no existe un estado a medias que reanudar; «reanudable» se cumple porque un reintento tras una caída parte de cero.
- **La base impone el resultado.** Una restricción `CHECK` por perfil: uno anonimizado **no puede** conservar contacto, expectativa salarial ni disponibilidad, ni ser descubrible (y el profesional no conserva titular, biografía ni foto), y uno vivo **debe** tener correo. Así ni un error futuro reabre un perfil anonimizado en las sugerencias.
- **Un perfil anonimizado «no existe» para la aplicación.** Las lecturas y escrituras de ambos repositorios filtran `anonymized_at is null`: un viewer que pregunta por un candidato anonimizado ve `UNSET` y nadie puede editarlo. La cuenta ya no puede actuar (`resolveactor` la rechaza), esto es la segunda barrera.
- **Se borran también las habilidades, y se anulan el salario y la disponibilidad** (revisión de la regla del modelo, sin ADR bloqueado en contra: BR-DATA-05 enumera «nombre, contacto, texto de CV, embedding» y ADR-0013 no menciona habilidades). `data-model.md` §4.12 decía que las habilidades se conservaban y §4.4 que las preferencias también. Se cambia por dos razones. **Huella:** un conjunto de habilidades específicas es casi único y, junto con el `account_id` que se conserva para enlazar las postulaciones, reidentifica sin necesidad de ningún texto sensible; el texto libre es un riesgo menor. **Minimización:** nada las lee tras anonimizar (el snapshot devuelve vacío, la API ve «sin perfil», `matching` anula el embedding y los scores guardados son independientes), y conservar un dato sin finalidad es lo que hay que justificar. Por el mismo motivo se anulan la expectativa salarial (el dato más sensible de la fila) y la disponibilidad. Solo se conservan país, modalidad, jornada y reubicación, que son gruesos. La asimetría favorece borrar: lo retenido se puede borrar después, pero restaurar lo anonimizado contradiría su propósito. Los idiomas y las autorizaciones de trabajo ya se borraban.
- **Cambia BR-DATA-05 y el borrador de privacidad.** BR-DATA-05 ahora enumera habilidades, idiomas, autorizaciones, salario y disponibilidad, y el borrador de política de privacidad lo dice al describir qué se anonimiza. Ese borrador sigue sin revisión legal: este cambio es más protector que lo que prometía, pero el texto final lo valida un abogado.
- **Se borra la lista de exclusión del candidato anonimizado**, y se **conservan** las filas donde él es la cuenta excluida (no contienen datos suyos), como decía `data-model.md` §4.10.
- **Un solo evento por cuenta**, `profile.anonymized` (el `AccountAnonymized` de §7, con un nombre que no se confunde con el evento de cuenta de `auth`). Se asocia al perfil profesional, que es el agregado raíz, con la versión que dejó la anonimización; la carga lleva solo `accountId` y la versión. `matching` anula el bloque de embedding y fuerza su copia de `discoverable` a `false` al recibirlo. Una cuenta sin nada que anonimizar no emite evento, así que el lote puede reintentar sin riesgo.
- **La anonimización incrementa la versión** de cada perfil, para que cualquier cliente con una versión vieja falle en vez de escribir sobre un perfil ya anonimizado.

**Pregunta abierta que no es de `cv`:** la copia del perfil canónico al postular (`application_profile_snapshots`, ADR-0017 decisión 9) es donde vive el historial que BR-DATA-05 manda preservar, y probablemente retiene habilidades vinculadas al `account_id`. El argumento de la huella aplica igual allí; si lo conserva, el borrado de `cv` protege menos de lo que parece. Se registra como D5 del TDD de `matching`.

**Pendiente de este sub-paso, no implementado:** el lote que recorre las cuentas vencidas, llama a Clavaris (BR-DATA-06) y a este caso de uso; su dueño lo decide el TDD de «Cumplimiento y ciclo de vida de cuenta», que no existe aún. Tampoco el consumo del evento por `matching` (su propio TDD). Con esto `cv` completa su diseño de §10: los seis sub-pasos están hechos.

Cada sub-paso sale como una PR propia con su definición de hecho completa (OpenAPI anotado, política de límite de tasa, métrica y log con `traceId`, prueba de autorización y de CSRF).


### 10.7 Sub-paso 7: el CV (ADR-0021)

**Tabla `candidate_cv_files` (M8).** Una fila por versión, nunca se reescribe el archivo (ADR-0021 §5):

| Columna | Tipo | Regla |
|---|---|---|
| `id` | uuid | PK; es lo que guarda `ats` en la postulación |
| `candidate_profile_id` | uuid | FK a `candidate_profiles` |
| `object_key` | varchar | Clave del almacén (`cv/{uuid}`), única; nula tras el borrado físico |
| `display_name` | text (≤ 100, `CHECK`) | Nombre saneado (`FileContentRules.displayName`); es un dato personal, nulo al anonimizar |
| `file_type` | varchar | `PDF` o `DOCX`, el tipo **detectado** |
| `size_bytes` | integer | Mayor que 0 |
| `sha256` | char(64) | Para no crear otra versión si se sube el mismo archivo |
| `is_current` | boolean | Una sola vigente por perfil (índice único parcial) |
| `uploaded_at` | timestamptz | |
| `deleted_at` | timestamptz | Al anonimizar; sin ella la fila sigue sirviendo |

**`uploadcv`, en este orden.** Capacidad de candidato → perfil (404 si no hay: el CV es del perfil de candidato) → tamaño (`TOO_LARGE`) → tipo real PDF o DOCX sin macros (`UNSUPPORTED_TYPE`) → si el SHA-256 es el del vigente, devuelve el vigente sin crear nada → tope de versiones (`LIMIT_REACHED`) → escaneo (`MALWARE_DETECTED`; la firma va al log, el archivo no) → `put` → bloqueo del perfil (`FOR UPDATE`), nueva comprobación del tope y alta de la versión vigente. Si algo falla después del `put`, se borra el objeto por *best effort*; un fallo al confirmar la transacción deja un objeto huérfano que no referencia nadie (la reconciliación periódica del ADR-0021 §2 queda en el registro de deuda).

**El escaneo y la subida van fuera de la transacción** (TD-044, 27-nov-2026; antes iban dentro). El diseño inicial lo aceptaba porque el límite `upload` es de 10 por hora *y cuenta*, pero ese límite no es global: diez subidas simultáneas de cuentas distintas agotaban el pool por defecto mientras ClamAV y el almacén respondían. Ahora las comprobaciones previas son lecturas sin transacción. El escaneo y el `put` no retienen conexión, y solo el bloqueo del perfil, la nueva comprobación del tope y el alta van en una transacción corta (`TransactionOperations`, inyectada por `CvFileSources`). La foto (§10.8) sigue el mismo esquema, con el cambio de foto y su evento de borrado en esa transacción.

**Límites:** 5 MB y 20 versiones por cuenta (`jobseeker.cv.cv-max-bytes`, `jobseeker.cv.cv-max-versions`), las decisiones R2 y R3 del ADR-0021.

**Anonimización:** `anonymizeprofiledata` marca `deleted_at`, borra `display_name` y `object_key`, y en la misma transacción emite `storage.file_deletion_requested` con las claves; un consumidor de la bandeja las borra del almacén, con reintentos (ADR-0021 §6).

**Sin evento de dominio al subir:** `matching` no lee el CV (embebe datos estructurados, nunca el archivo; amenaza T-N11) y la preparación para postular (`checkapplicationreadiness`) consulta la tabla. Si una notificación lo necesitara, se añade entonces.

**Lo que se construyó además del diseño:**

- **La política de límite `upload` se asigna por tipo de contenido** (`multipart/`), no por ruta: cualquier subida futura (la foto, las respuestas de archivo) queda limitada sin tocar las reglas.
- **El límite de `multipart` de Spring es de 6 MB**, algo mayor que el del CV: un archivo de 5 a 6 MB recibe el `FILE_REJECTED` del caso de uso y uno mayor se corta antes de leerlo, con el mismo `422 FILE_REJECTED / TOO_LARGE`, no un `413` genérico.
- **`cv` gana la dependencia `spring-boot`** para `CvFileProperties` (`jobseeker.cv.cv-max-size`, `jobseeker.cv.cv-max-versions`), como `job` y `matching`.
- **Reconciliación de huérfanos (TD-041, resuelta).** `reconcileorphancvfiles`, disparado cada 6 h por un planificador de `cv` (15 min después de arrancar), lista los objetos `cv/` por páginas y borra los que no tienen versión viva. Cubre dos casos: un alta que falló al confirmar y un borrado por anonimización que agotó sus reintentos. Reglas de seguridad: no juzga objetos de menos de 24 h (una subida en curso), nunca borra una clave con un formato que la aplicación no crea y, si una pasada encuentra más de 500 huérfanos, **no borra ninguno** y lo registra como error (una base restaurada desde una copia anterior al almacén los haría parecer huérfanos a todos). Cada pasada examina hasta 10 000 objetos y la siguiente sigue donde quedó.
### 10.8 Sub-paso 8: la foto de perfil y las pantallas de subida (ADR-0021)

**Columnas en `professional_profiles`** (migración `V20261126130000`): `profile_photo_url` desaparece (nunca se escribió: D6) y la sustituyen `profile_photo_key` (`photo/{uuid}`, `CHECK` de formato) y `profile_photo_type` (`PNG` o `JPEG`). Un `CHECK` exige las dos o ninguna, y el de anonimización pasa a exigir la clave nula.

**`uploadprofilephoto`, en este orden.** Perfil profesional (404 si no hay; la foto es de la cuenta, no de una capacidad) → tamaño, 2 MB (`TOO_LARGE`) → tipo real PNG o JPEG (`UNSUPPORTED_TYPE`) → escaneo **del original** (`MALWARE_DETECTED`) → re-codificación → `put` de la imagen re-codificada → bloqueo del perfil (`FOR UPDATE`) y nueva clave → si había otra foto, `storage.file_deletion_requested` con la anterior. Un fallo después del `put` borra el objeto nuevo por *best effort*.

**Re-codificación con ImageIO del JDK** (`ImageIoPhotoProcessor`), sin dependencias nuevas. Antes de decodificar lee solo la cabecera y rechaza más de 40 megapíxeles: una imagen pequeña que dice medir 50 000 × 50 000 agotaría la memoria (bomba de descompresión). Reduce a 512 px de lado mayor y escribe una imagen nueva desde los píxeles, de modo que **no se copia ningún metadato** (EXIF, ubicación GPS); una prueba lo comprueba con un segmento EXIF real. Una imagen que ImageIO no sabe leer se rechaza como `UNSUPPORTED_TYPE`. Límites en `jobseeker.cv.photo-max-size`, `photo-max-side` y `photo-max-pixels`.

**Desvío del ADR-0021 §3: la foto no se descarga por URL firmada, la sirve la aplicación.** La CSP de las pantallas es `img-src 'self' data:`: una `<img>` que apunte (o redirija) a la URL firmada del almacén no se cargaría. Abrir la CSP al dominio del almacén exigiría además URLs firmadas nuevas en cada render, que no se pueden cachear. Como la imagen ya está reducida (decenas de KB), `getmyprofilephoto` la lee del almacén (`ObjectStoragePort.get`, nuevo) y la aplicación la devuelve con `Cache-Control: private, max-age=300`. La autorización es la misma que el ADR exige: solo el dueño la pide, por su sesión o su token. El parámetro `?v=` de la `<img>`, derivado de la clave aleatoria, cambia con cada foto, así que la caché nunca muestra la anterior.

**Quitar la foto** (`removeprofilephoto`) es idempotente: quitar una que no hay no es un error ni emite evento.

**Anonimización:** `anonymizeprofiledata` descarta la foto **antes** de anonimizar el perfil (después la clave ya sería nula) y pide su borrado **en el mismo evento** que el de los CV, asociado al perfil raíz.

**Reconciliación (TD-041):** recorre también el prefijo `photo/`, con su propio cursor, y compara contra `profile_photo_key`. Así queda cubierta una foto reemplazada cuyo borrado agotó los reintentos.

**API:** `POST`, `GET` y `DELETE` en `/api/v1/me/professional-profile/photo`. El `POST` responde `201` con el tipo y la etiqueta, y el `GET` devuelve la imagen.

**Pantallas (Thymeleaf + HTMX, ADR-0005):**

- `/app/candidate-cv` (enlazada desde el inicio y desde el perfil de candidato) muestra la versión vigente, la lista de versiones con sus enlaces de descarga (`302` a la URL firmada, como la API) y el formulario de subida `multipart` por HTMX (`hx-encoding`). El token CSRF viaja en la cabecera que pone la página, así que la cadena web lo comprueba sin leer el cuerpo (ADR-0020); el barrido de rutas lo verifica.
- La sección de la foto en `/app/profile` se carga aparte (`hx-trigger="load"`) para que cambiarla no recargue el formulario del perfil ni pierda lo que se esté escribiendo. Sus errores vuelven a la propia sección con `HX-Retarget`/`HX-Reswap`, que HTMX aplica después de la regla `422` de la página.
- **Un rechazo o un antivirus caído responden `422` con el motivo en castellano en las pantallas** (la API JSON sí distingue el `503 UPSTREAM_UNAVAILABLE`): es el código con que estas páginas reemplazan su zona de errores, y lo que el usuario necesita es leer qué pasó. Los mensajes nunca repiten el nombre del archivo (BR-DATA-01).
- `cv` gana `jakarta.servlet-api` con alcance `provided`, solo para fijar esas cabeceras desde el controlador de la pantalla.

## 11. Pruebas

- **Dominio y casos de uso:** unitarias sin Spring, con puertos en memoria. Cada invariante de §3 y cada error de §6 tiene su caso, y se pasa PIT con los mutadores `STRONGER` como en `auth`.
- **Adaptador JDBC:** integración con Postgres 17 real (Testcontainers): crear y editar, el bloqueo optimista con dos escrituras concurrentes, y que las restricciones únicas y los `CHECK` de la migración hacen su trabajo.
- **Salario:** que un importe sin moneda o periodo se rechaza, que `mínimo > máximo` se rechaza y que el rango es opcional completo (BR-CAND-07).
- **Autorización:** un actor sin capacidad de candidato recibe `403` en cada ruta de candidato; **nadie puede leer ni escribir el perfil de otro** (la ruta no admite identificador de cuenta, y se prueba que un identificador en el cuerpo se ignora).
- **Privacidad:** una prueba que ejecuta cada caso de uso con datos reconocibles y comprueba que ni el log ni las etiquetas de métricas ni la carga de los eventos de la bandeja los contienen (BR-DATA-01).
- **Anonimización:** que tras ejecutar `anonymizeprofiledata` no queda ningún dato identificable de `cv` y que ejecutarla dos veces da el mismo estado.
- **Eventos:** que cada operación emite exactamente su evento en la misma transacción, y que **no** emite `ProfileUpdated` sin capacidad de candidato.
- **Reglas de arquitectura:** ARCH-01 a 14 sobre el módulo; en particular, ningún tipo de `cv` sale de su superficie pública.
- **Contrato con `matching`:** una prueba en el adaptador de `matching` que lee el snapshot y comprueba que no contiene nombre, foto, correo, teléfono ni nombre de institución.

## 12. Decisiones tomadas

Las siete preguntas que dejó la primera versión de este documento están resueltas; ninguna bloquea ya un sub-paso. La que más cambió respecto a lo recomendado inicialmente es D1.

| ID | Pregunta | Resolución |
|---|---|---|
| **D1** | BR-CAND-01 exige «un CV cargado» para postular, pero la subida de archivos no existe hasta el ADR-0021 | **La regla no cambia.** La primera recomendación era relajarla a «perfil estructurado mínimo»; al revisar se vio que no hace falta: la hoja de ruta ya incluye el bloque «Almacenamiento de archivos» en la Ola 1 y BR-CAND-01 es una precondición de **postular**, no de **tener perfil**. Se resuelve ordenando: ese bloque se entrega antes de «Postulación y pipeline mínimos» (anotado en `roadmap-and-release-plan.md` §3). Mientras tanto el candidato construye su perfil completo sin archivo, y el score se alimenta de datos estructurados (§7), así que el archivo no es insumo del matching. Cuando llegue, `cv` añade su caso de uso de subida y `getprofilesnapshot` expone un indicador «tiene CV», que es lo que comprueba la postulación |
| **D2** | Origen del nombre en el primer guardado | **Se pide en pantalla.** Clavaris no entrega el nombre (hallazgo G4 del spike: sin claim `name`), así que no hay nada que precargar. El claim `picture` que sí entrega no se usa (D6) |
| **D3** | Moneda de la expectativa salarial | **Moneda y periodo por registro**, como ya diseñó `03-lld.md` §4. Se comparan solo importes de la misma moneda, llevados al mismo periodo, sin conversión (BR-CAND-07, BR-JOB-21, BR-MATCH-10). Una moneda distinta excluye la dimensión del score, y lo dice el desglose |
| **D4** | Catálogos cerrados | **Aprobados** (§3.1) y con su `CHECK` en el modelo |
| **D5** | Qué pasa con el perfil si se deshabilita la capacidad | **La fila se conserva** y reactivar la capacidad recupera los datos. Que deje de ser descubrible lo hará `cv` al reaccionar al cambio de capacidad que emitirá `auth` cuando exista su caso de uso (hoy `auth` no desactiva capacidades: §7 de su TDD). Hasta entonces no hay forma de apagar una capacidad, así que no hay estado que cubrir |
| **D6** | Foto de perfil | Sin aceptar URLs externas (filtrarían la visita del usuario a un tercero). **Activada con el ADR-0021** (§10.8): la foto se sube, se re-codifica y la sirve la propia aplicación |
| **D7** | Habilidades como texto libre | **Aceptado**, con `skill_id` ya previsto en la tabla para que el ADR-0019 añada la normalización sin reescribirla |

**Lo que sigue abierto y no es de este documento:** quién es dueño del lote de eliminación de cuentas (§9) y qué pasa con las vacantes publicadas al desactivar la capacidad de reclutador (`domain-model.md` §8).

## 13. No cubierto todavía

- La foto de perfil vista por **otros** (el reclutador en su pipeline): hoy solo la ve su dueño. Llega con `ats`, que decidirá si la muestra (§10.8).
- `RecruiterProfile` y las plantillas y exportaciones de marca (Ola 3).
- Generación del CV propio en PDF (Ola 3).
- La orquestación de la eliminación de cuenta y la cancelación al volver a iniciar sesión (bloque de cumplimiento).
- Las vistas de un candidato desde el pipeline y desde los sugeridos (TDD de `ats` y de `matching`).
- La notificación a un candidato por cambios en su perfil: ninguna regla la pide.
