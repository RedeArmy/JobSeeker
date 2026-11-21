# ADR-0028: PostgreSQL 17 en desarrollo y CI, por paridad con Supabase
## Sistema Inteligente de Análisis de Compatibilidad CV-Vacante con CRM de Postulaciones

| Campo | Valor |
|---|---|
| **Estado** | ✅ Aprobado — 20-nov-2026, por el responsable del proyecto |
| **Versión** | 1.0 |
| **Fecha** | Noviembre 2026 |
| **Documento relacionado** | ADR-0002, ADR-0026, ADR-0027, `data-model.md` §2 y §5.1, `09-verificacion-supabase-hibernate-despliegue.md`, `10-spike-r13-sprint-0.md` |

---

## Contexto

La documentación fijaba **PostgreSQL 16 + pgvector** (ADR-0002, `system-design-document.md`, README). Dos hechos obligan a corregirlo:

1. **Supabase no ofrece la versión 16.** Según las fuentes consultadas en noviembre de 2026 (secundarias; la confirmación definitiva es el ajuste *Service versions* del panel de un proyecto), Supabase aloja las versiones 15 y 17, los proyectos nuevos reciben la 17, la 14 terminó su soporte el 1-jul-2026 y la 18 todavía no está disponible. Producción irá, por tanto, sobre **17**.
2. **El responsable pidió no depender de recursos de Supabase durante el desarrollo** y usar una base local, pasando a Supabase solo en producción. Eso ya es el plan (Postgres en Docker local, ADR-0026) y se mantiene. Se propuso además usar la última versión (18) en local.

El problema de desarrollar en una versión mayor distinta de la de producción es que las diferencias aparecen tarde y en el peor lugar: una migración o una consulta que funciona en 18 puede fallar en 17 el día del despliegue (por ejemplo `uuidv7()` nativo, columnas generadas virtuales o `RETURNING OLD/NEW`, todas novedades de la 18), y el planificador, que decide si el índice HNSW se usa, cambia entre versiones mayores. La regla del proyecto es que Flyway aplica el mismo esquema, igual, en local y en Supabase (`data-model.md` §2).

## Decisión

1. **PostgreSQL 17 (última versión menor 17.x) en el Postgres local de desarrollo, en CI y en Testcontainers**, con la imagen `pgvector/pgvector:pg17` (pgvector 0.8.7, que cumple el mínimo de 0.8.0 para `hnsw.iterative_scan`). Cuando los archivos de compose y de CI sean reales, la imagen se fija por *digest*.
2. **Desarrollo sin Supabase.** El stack local (Postgres, Redis, Ollama, MinIO como reemplazo de Storage, Mailpit) basta para trabajar; Supabase entra en staging y producción (ADR-0026). Nada del código depende de un servicio de Supabase salvo mediante interfaces estándar (JDBC, S3).
3. **Regla de paridad:** la versión mayor de desarrollo y CI es la que Supabase ofrece a los proyectos nuevos. Un cambio de versión mayor es una decisión planificada con su propio ADR y una ejecución previa de todo el CI sobre la versión nueva; no se hace por defecto ni por Dependabot (`postgres` queda excluido de las actualizaciones mayores).
4. **Las funciones exclusivas de PostgreSQL 18 no se usan** mientras Supabase no ofrezca la 18. No hace falta una regla aparte que lo vigile: como todo el CI corre sobre 17, una migración que las use falla antes de llegar a `main`.
5. **Revisión:** cuando Supabase ofrezca la versión 18, se abre un ADR que lo evalúe, con el CI corriendo en las dos versiones durante la transición.

## Alternativas consideradas

| Alternativa | Por qué no |
|---|---|
| **PostgreSQL 18 solo en desarrollo** | Es la versión más reciente, pero rompe la paridad con producción (17): los errores de migración o de planificador aparecerían al desplegar. |
| **PostgreSQL 18 en desarrollo y CI sobre 17 y 18** | Detecta las diferencias, pero duplica el tiempo y el costo del CI para ganar solo funciones que no se podrían usar en producción de todos modos. |
| **Mantener PostgreSQL 16** | Era el supuesto documentado, pero Supabase no lo ofrece: se desarrollaría contra una versión que nunca estará en producción. |

## Consecuencias

- **Positivas:** una sola versión mayor de punta a punta, lo medido localmente vale para producción y el desarrollo no necesita una cuenta ni un proyecto de Supabase.
- **Negativas / trade-offs aceptados:** no se usan las novedades de la 18 hasta que Supabase la ofrezca; el supuesto de que Supabase ofrece la 17 y no la 18 se apoya en fuentes secundarias hasta confirmarlo en el panel.
- **Lo medido sobre 16 se está repitiendo sobre 17:** el *spike* R13 (8 pruebas) ya pasó sin cambios sobre PostgreSQL 17.12 con pgvector 0.8.7; el experimento de recall de HNSW (`anexos/embeddings-esquema/recall`) está en ejecución sobre 17 y su resultado se añadirá al anexo cuando termine. Las mediciones anteriores se conservan como historial de lo observado en 16.16.

## Acciones derivadas

- [x] Sustituir «PostgreSQL 16» por 17 en README, `system-design-document.md`, ADR-0002 (nota de enmienda), ADR-0026, ADR-0027, el HLD y el comentario de `docker-compose.yml`.
- [x] Cambiar la imagen de la prueba de integración del anexo `spike-r13` a `pgvector/pgvector:pg17`.
- [ ] Sprint 0: fijar `pgvector/pgvector:pg17` por *digest* en el compose real y en el CI.
- [ ] Añadir al anexo de recall el resultado sobre PostgreSQL 17 y compararlo con el de 16.
- [ ] Antes de producción: confirmar en un proyecto de Supabase la versión de Postgres y de pgvector (`10-spike-r13-sprint-0.md` §6).

## Preguntas abiertas

- ¿Qué versión menor de pgvector tiene el proyecto de Supabase real? Se cierra al crear el proyecto de staging.
