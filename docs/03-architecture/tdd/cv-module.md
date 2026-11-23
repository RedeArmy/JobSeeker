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
| `CandidateProfile`: contacto, habilidades, idiomas, autorizaciones de trabajo, atributos de elegibilidad, disponibilidad, descubribilidad (BR-CAND-04, BR-DATA-03) | Subida, análisis y almacén del archivo de CV, y la foto: ADR-0021 (aún sin escribir, bloque «Almacenamiento de archivos») |
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
| `anonymizeprofiledata` | Anonimiza los datos de `cv` de una cuenta (§9) | interna (lote) | `AccountAnonymized` |

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

Las pruebas de la migración (la de «publicadas inmutables» y el análisis de `squawk`) existen desde el paso 2 del Sprint 0 y cubren estas migraciones sin trabajo extra. Las restricciones nuevas sobre tablas con filas se añaden `NOT VALID` y se validan después; en tablas nuevas, como estas, no hace falta.

## 9. Anonimización (BR-DATA-05)

`anonymizeprofiledata` recibe una cuenta y anonimiza **solo los datos de `cv`**, como dice `data-model.md` §4.2 y §4.4: nombre al marcador genérico, titular, biografía y foto a nulo; contacto y texto de CV a nulo; el vector del perfil **no** está en `cv` sino en `matching`, que lo anula al recibir `AccountAnonymized` (`data-model.md` §4.7.1); `discoverable` forzado a `false`; las filas hijas y las autorizaciones e idiomas se eliminan físicamente; el resto de atributos de preferencia se conserva porque no identifica a nadie por sí solo. La lista de exclusión del candidato anonimizado se elimina (`data-model.md` §4.10).

**Idempotente y reanudable** (`03-lld.md` §8): cada paso comprueba su estado y un reintento tras una caída a mitad termina el trabajo, nunca lo repite con otro resultado. Emite `AccountAnonymized`, que invalida cualquier score en caché con datos derivados.

**Lo que este documento no decide:** qué módulo es dueño del lote que recorre las cuentas vencidas y llama a Clavaris (BR-DATA-06). `domain-model.md` §8 lo deja abierto y ADR-0013 describe el lote sobre los datos de `cv`; se resuelve en el TDD del bloque «Cumplimiento y ciclo de vida de cuenta». Este módulo solo promete el contrato anterior, que ese lote invoca por cuenta.

## 10. Orden de entrega

| Sub-paso | Entrega | Depende de |
|---|---|---|
| 1 | ✅ **Hecho (23-nov-2026).** Migración de `professional_profiles` (solo la raíz), `ProfessionalProfile` con sus reglas, `getmyprofessionalprofile` y `saveprofessionalprofile`, adaptador JDBC y el controlador JSON. **Es la rebanada plantilla del módulo**: lleva todas las transversales. Detalle y desvíos de este documento en §10.1 | — |
| 2 | Las tablas hijas y las cuatro colecciones (`manage*`), y **las pantallas HTMX del perfil** (nota en §10.1) | 1 |
| 3 | `CandidateProfile`: moneda y periodo (M1), `get`/`save`, habilidades, idiomas, autorizaciones | 1 |
| 4 | Descubribilidad, disponibilidad y lista de exclusión, con sus eventos | 3 |
| 5 | `getprofilesnapshot` y los eventos conectados a la bandeja; prueba de contrato con `matching` | 3, TDD de `matching` |
| 6 | `anonymizeprofiledata` | 2, 3, 4 |

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

Cada sub-paso sale como una PR propia con su definición de hecho completa (OpenAPI anotado, política de límite de tasa, métrica y log con `traceId`, prueba de autorización y de CSRF).

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
| **D6** | Foto de perfil | **Campo vacío en la Ola 1**, sin aceptar URLs externas (filtrarían la visita del usuario a un tercero). Se activa con el ADR-0021 |
| **D7** | Habilidades como texto libre | **Aceptado**, con `skill_id` ya previsto en la tabla para que el ADR-0019 añada la normalización sin reescribirla |

**Lo que sigue abierto y no es de este documento:** quién es dueño del lote de eliminación de cuentas (§9) y qué pasa con las vacantes publicadas al desactivar la capacidad de reclutador (`domain-model.md` §8).

## 13. No cubierto todavía

- Subida, almacenamiento y análisis del CV, y la foto (ADR-0021).
- `RecruiterProfile` y las plantillas y exportaciones de marca (Ola 3).
- Generación del CV propio en PDF (Ola 3).
- La orquestación de la eliminación de cuenta y la cancelación al volver a iniciar sesión (bloque de cumplimiento).
- Las vistas de un candidato desde el pipeline y desde los sugeridos (TDD de `ats` y de `matching`).
- La notificación a un candidato por cambios en su perfil: ninguna regla la pide.
