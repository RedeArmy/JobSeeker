# ADR-0027: Ubicación, versionado y propiedad de las migraciones Flyway
## Sistema Inteligente de Análisis de Compatibilidad CV-Vacante con CRM de Postulaciones

| Campo | Valor |
|---|---|
| **Estado** | ✅ Aprobado — 20-nov-2026, por el responsable del proyecto |
| **Versión** | 1.1 — aprobado el 20-nov-2026 |
| **Fecha** | Noviembre 2026 · aprobado el 20-nov-2026 |
| **Documento relacionado** | `data-model.md` §2 y §5.1, ADR-0001, ADR-0002, ADR-0015, ADR-0018, ADR-0026, `09-verificacion-supabase-hibernate-despliegue.md` §3 |

---

## Contexto

`data-model.md` §2 fija que Flyway es la única fuente de verdad del esquema, pero deja sin definir tres cosas que importan en cuanto haya seis módulos escribiendo migraciones en paralelo (trunk-based, ADR-0001):

1. **Dónde viven los archivos.** El esqueleto ya trae una carpeta `db/migration` por módulo de negocio, sin una regla que diga si es intencional.
2. **Cómo se numeran.** El documento dice `V{n}__descripcion.sql`. Con varios módulos y sin ramas largas, dos cambios independientes tomarían el mismo número o forzarían una coordinación manual.
3. **Quién es dueño de qué.** Hay claves foráneas entre contextos (`applications` apunta a `job_postings`, `candidate_profiles` y `compatibility_scores`), así que el orden de creación importa.

Además, tres afirmaciones del modelo de datos no se sostienen:

- **pgvector «en la primera migración de `matching`»:** `candidate_profiles` (contexto `cv`) y `job_postings` (contexto `job`) llevan columnas `embedding` (ADR-0018, `data-model.md` §5.1). Esas tablas se crean antes que las de `matching`, y `CREATE TABLE ... vector(N)` falla si la extensión no existe.
- **Migraciones `undo`:** son una función de pago (Flyway Teams); la edición Community no las ejecuta.
- **Una migración aplicada no puede cambiar:** Flyway valida el checksum al arrancar y se niega a iniciar si difiere. Es el incidente que ya sufrió `../Clavaris` en preproducción el 16-sep-2026, y se resolvió con una comprobación en CI.

`../Clavaris` opera hoy 126 migraciones repartidas en seis módulos con el esquema que aquí se adopta, de modo que la decisión no es especulativa.

## Decisión

1. **Una sola instancia de Flyway, en el módulo `app`**, con la ubicación por defecto `classpath:db/migration`. Cada módulo de negocio conserva su propia carpeta `src/main/resources/db/migration` y se empaqueta en su JAR; Flyway las ve todas como una sola secuencia al arrancar `app`.
2. **Versión por marca de tiempo:** `V<yyyyMMddHHmmss>__<modulo>_<descripcion>.sql` (por ejemplo `V20261201103000__cv_create_professional_profiles.sql`). El orden global lo da la marca de tiempo; el prefijo del módulo en la descripción hace visible la propiedad. Se prohíbe `outOfOrder`. La única excepción es `V1`, reservada a la extensión de la decisión 3.
3. **Extensiones de PostgreSQL en `app`:** `V1__enable_extensions.sql` en `app/src/main/resources/db/migration` crea `vector` (pgvector, ADR-0002) antes que cualquier tabla. En Supabase la extensión se crea con el rol que ya tiene permiso para ello; en Docker local, con el usuario de la base.
4. **Propiedad por contexto.** Un módulo solo crea o altera las tablas de su contexto:

   | Contexto | Tablas |
   |---|---|
   | `auth` | `accounts` |
   | `cv` | `professional_profiles`, `experiences`, `education_entries`, `certifications`, `projects`, `candidate_profiles`, `recruiter_profiles`, `candidate_availability_exclusions`, `candidate_work_authorizations`, `candidate_skills`, `candidate_languages`, `recruiter_templates`, `branded_cv_exports` |
   | `job` | `job_postings`, `eligibility_rules`, `candidate_invitations`, `job_posting_scheduled_transitions`, `application_questions` |
   | `matching` | `compatibility_scores`, `profile_embeddings`, `job_embeddings` (los vectores y la copia de `discoverable` / `published`; `data-model.md` §4.7.1, añadidas el 23-nov-2026 al mover los vectores fuera de `cv` y `job`) |
   | `ats` | `applications`, `application_answers`, `application_follow_up_requests`, `application_follow_up_items` |
   | `app` | `outbox_events`, `processed_events`, `idempotency_keys`, `audit_log` (infraestructura transversal; ADR-0016) |

   `tracker` no es dueño de tablas: opera sobre `applications`. Las tablas de `app` son de infraestructura y **no participan en claves foráneas** (ni como origen ni como destino): el historial de auditoría y la deduplicación deben sobrevivir a la anonimización de una cuenta. Los nombres salen de `data-model.md` §4; si el modelo cambia, este cuadro se actualiza en el mismo cambio.
5. **Orden de dependencias entre contextos:** `auth` → `cv` → `job` → `matching` → `ats`. Una clave foránea solo puede apuntar «hacia la izquierda» de esa cadena, y la marca de tiempo de la migración que la crea debe ser posterior a la de la tabla referenciada. Una prueba que construye el esquema desde una base vacía lo comprueba.
6. **Hacia adelante solamente (*forward-only*).** No hay migraciones `undo`. El retroceso es desplegar la versión anterior de la aplicación y, si el esquema ya cambió, una migración nueva que lo corrige. Los cambios incompatibles se hacen en fases *expandir/contraer* (añadir, doble escritura, migrar, retirar), como ya describe `data-model.md` §5.1 para el cambio de modelo de embeddings.
7. **Inmutabilidad.** Una migración que ya llegó a `main` no se edita ni se renombra. CI compara cada rama con `origin/main` y falla si cambió o desapareció un archivo ya publicado (script equivalente al de Clavaris, con historial completo en el *checkout*).
8. **Índices sin bloqueo.** `CREATE INDEX` sobre una tabla con datos usa `CONCURRENTLY`, y por eso `spring.flyway.postgresql.transactional-lock=false` (con el bloqueo transaccional por defecto, `CONCURRENTLY` espera a la propia transacción de Flyway). CI rechaza un `CREATE INDEX` sin `CONCURRENTLY` fuera de una tabla creada en la misma migración.
9. **Conexión:** Flyway usa la conexión directa o en modo sesión (puerto 5432 de Supabase), nunca el *pooler* transaccional (6543), porque necesita bloqueos de sesión (`09-…` §3). La aplicación sí usa el 6543.
10. **Comprobaciones obligatorias en CI**, que se añaden con el primer módulo que migre: el esquema se construye desde cero sobre una imagen de Postgres 17 con pgvector (ADR-0028); el esquema de nombres (decisión 2); la propiedad por contexto (el prefijo del archivo coincide con la carpeta del módulo y el DDL solo nombra tablas de ese contexto); el orden de dependencias (decisión 5); la inmutabilidad (decisión 7) y los índices sin bloqueo (decisión 8).
11. **Linter de seguridad de migraciones: squawk** (versión y suma de control fijadas en `scripts/fetch-ci-tool.sh`). Analiza cada migración con el parser de Postgres y avisa de bloqueos, cambios que rompen a la aplicación en marcha y operaciones destructivas (`DROP TABLE`, `DROP COLUMN`, columnas `NOT NULL` sin valor por defecto). Corre en CI sobre todas las migraciones reales y su configuración es `.squawk.toml`; una excepción puntual se marca en el SQL con `-- squawk-ignore <regla>` y un comentario que diga por qué es segura. Cinco reglas están desactivadas, cada una con su motivo en el archivo (p. ej. `require-concurrent-index-creation`, porque exige `CONCURRENTLY` también sobre tablas recién creadas, imposible dentro de la transacción de Flyway; la regla correcta es la de la decisión 8).
12. **Tiempo de espera de bloqueos.** Toda conexión de Flyway fija `lock_timeout = 10 s` (`spring.flyway.init-sqls`): una migración que espere más de eso un bloqueo falla en vez de dejar sin servicio las escrituras de producción.

> **Enmendado el 20-nov-2026, al implementarlo:** se añadió la fila `app` a la tabla de propiedad (decisión 4) y las decisiones 11 y 12. Las comprobaciones de las decisiones 2, 4, 5 y 8 son `MigrationRules`/`MigrationConventionsTest`; la 7, `scripts/check-migration-immutability.sh` (con sus pruebas); la 10, `MigrationsIntegrationTest`; todas corren en CI (`ci-backend` y `repo-checks`). Los archivos de ejemplo bajo `docs/` no cuentan como migraciones.

## Alternativas consideradas

| Alternativa | Por qué no |
|---|---|
| **Todas las migraciones en `app`** | Un solo lugar, pero rompe el límite del módulo: el módulo `cv` no podría probarse con su esquema sin arrastrar el de los demás, y el dueño de cada tabla dejaría de verse en la estructura de carpetas. |
| **Una instancia de Flyway por módulo (tablas de historial separadas)** | Aísla los módulos, pero obliga a coordinar el orden de arranque para las claves foráneas entre contextos y multiplica las tablas de historial y los bloqueos de sesión. Con un solo desplegable no aporta nada. |
| **Versiones `V{n}` secuenciales** | Dos módulos que migran el mismo día toman el mismo número; resolverlo exige una coordinación manual que en trunk-based con un solo desarrollador termina en un `outOfOrder` o en un renombrado de algo ya publicado. |
| **Flyway Teams para tener `undo`** | Costo de licencia para una capacidad que la estrategia hacia adelante no necesita. |
| **Liquibase** | Cambiar de herramienta sin una razón de fondo; Flyway ya es decisión del modelo de datos y de `../Clavaris`. |

## Consecuencias

- **Positivas:** ninguna coordinación de números entre módulos; la propiedad es visible en el nombre del archivo y en la carpeta; las reglas son comprobables mecánicamente; se reutiliza un esquema que ya opera 126 migraciones en `../Clavaris`.
- **Negativas / trade-offs aceptados:** una marca de tiempo no dice nada del orden lógico (la cadena de dependencias de la decisión 5 hay que respetarla a mano y la prueba la verifica); dos desarrolladores que migren el mismo segundo podrían colisionar (improbable, y lo detecta Flyway al arrancar); no hay retroceso automático del esquema.
- **Hay que corregir** `data-model.md` §2, `roadmap-and-release-plan.md` §5, `integration-design.md` y la fila «Migraciones» de la estrategia de pruebas de `03-lld.md`, que hoy hablan de `V{n}`, de `undo` y de pgvector en `matching`.

## Acciones derivadas

- [x] Corregir `data-model.md` §2 (versionado, *forward-only* y ubicación de pgvector) y las menciones a `undo` en `roadmap-and-release-plan.md`, `integration-design.md` y `03-lld.md`.
- [x] Hecho el 20-nov-2026: `V1__enable_extensions.sql` en `app`, las tablas iniciales y `MigrationsIntegrationTest`, que construye el esquema desde cero sobre Postgres 17 con pgvector.
- [x] Hecho el 20-nov-2026: las comprobaciones de las decisiones 2, 4, 5 y 8 (`MigrationRules`, con pruebas negativas), el script de la decisión 7 y el linter de la decisión 11, todo en CI.
- [x] Hecho el 20-nov-2026: aprobado por el responsable del proyecto y añadido a la lista de ADRs aprobados.

## Preguntas abiertas

- ¿La propiedad por contexto debe verificarse con un analizador de SQL o basta una expresión regular sobre los nombres de tabla? Se decide con la primera migración real; la regex es suficiente mientras el DDL sea sencillo.
- Datos semilla (catálogos, roles): ¿migraciones normales o *repeatable* (`R__`)? Se decide al aparecer el primer catálogo; no bloquea esta decisión.
