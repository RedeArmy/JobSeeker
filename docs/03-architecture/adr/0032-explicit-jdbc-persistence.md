# ADR-0032: Persistencia con JDBC explícito (`JdbcClient`) en lugar de Spring Data JPA
## Sistema Inteligente de Análisis de Compatibilidad CV-Vacante con CRM de Postulaciones

| Campo | Valor |
|---|---|
| **Estado** | ✅ Aprobado — 27-nov-2026, a solicitud del responsable del proyecto, tras la revisión técnica de la sección final (asistida por IA, mismo mecanismo que los ADR-0030 y ADR-0031) |
| **Versión** | 1.0 |
| **Fecha** | 27-nov-2026 |
| **Documento relacionado** | ADR-0001, ADR-0002, ADR-0014, ADR-0015 (ARCH-07), ADR-0016, ADR-0027, `docs/05-engineering/coding-standards.md` §8.1, TD-015 |

---

## Contexto

La línea base del proyecto (ADR-0014 y los documentos de arquitectura) nombraba **Spring Data JPA / Hibernate** como capa de persistencia, y la revisión de noviembre verificó que Hibernate 7.4 mapea los vectores de pgvector (`09-verificacion-supabase-hibernate-despliegue.md`). Pero desde el primer módulo (`auth`) los adaptadores se escribieron con **`JdbcClient` y SQL explícito**, y hoy los cinco módulos con persistencia lo usan: 21 adaptadores en `infrastructure.adapter.out.persistence` y otros 2 transversales en `app` (bandeja y auditoría). Ningún `pom.xml` declara JPA ni Hibernate y no hay ninguna `@Entity`. La decisión existía en el código, pero no estaba escrita (TD-015).

Lo que la persistencia de este sistema necesita, y por qué pesa en la elección:

- **Escrituras condicionales en una sola sentencia**: el bloqueo optimista por versión (`update … where version = :esperada`), la lápida de anonimización comprobada dentro del `insert` (con `FOR SHARE`, TD-043) y la combinación del motivo más fuerte en el *upsert* del puntaje (`ON CONFLICT … DO UPDATE` con `CASE`).
- **Concurrencia explícita**: `FOR UPDATE SKIP LOCKED` en la bandeja de salida y en la cola de embeddings (ADR-0016), testigos de propiedad por intento, candados de aviso por cuenta (`pg_advisory_xact_lock`) en la puerta de publicar y en la cuota de los sugeridos.
- **Lotes de costo constante**: `unnest` sobre arreglos paralelos y `= any(:ids)` en lugar de una sentencia por fila (`coding-standards.md` §8.1), medidos contando sentencias.
- **pgvector**: búsqueda HNSW con `ORDER BY embedding <=> :vector` y el escaneo iterativo configurado por transacción (TD-012).
- **Modelo de dominio sin anotaciones** (ARCH-01): los agregados son registros inmutables; un ORM exigiría clases persistentes paralelas y su conversión.

## Decisión

1. **La persistencia es JDBC explícito con `JdbcClient`**, en los adaptadores de `infrastructure.adapter.out.persistence` de cada módulo. No se añade Spring Data JPA, Hibernate ni Spring Data JDBC.
2. **El SQL vive en constantes o en literales**, nunca se arma con datos de la petición: solo los valores viajan como parámetros con nombre. Una sentencia que necesita variantes se escribe entera para cada una (como `JdbcCvFileRepository.referenced`) en lugar de concatenar fragmentos en tiempo de ejecución.
3. **Las listas de columnas repetidas son constantes** de la clase del adaptador, y los parámetros comunes de un agregado se arman en un solo método (TD-015).
4. **Cada adaptador tiene su prueba de integración contra PostgreSQL 17 real** (Testcontainers, ADR-0028): lo que comprueba es el SQL, no un mapeo.
5. **ARCH-07 se mantiene** (`@Entity` solo en persistencia): no tiene efecto hoy y protege si algún día se adopta un ORM con un ADR que reemplace a este.

## Alternativas consideradas

| Alternativa | Por qué no |
|---|---|
| **Spring Data JPA / Hibernate** (la línea base escrita) | Las operaciones centrales (escrituras condicionales en una sentencia, `SKIP LOCKED`, `ON CONFLICT` con reglas de combinación, candados de aviso, lotes con `unnest`) se escribirían igualmente como SQL nativo dentro de `@Query`; el ORM añadiría el contexto de persistencia, la conversión entre agregados inmutables y entidades mutables y la reflexión que ADR-0014 pide evitar, sin ahorrar el SQL difícil |
| **jOOQ** | SQL con tipos comprobados al compilar, pero exige generar código desde el esquema en el *build* y su soporte de pgvector y de algunas construcciones de PostgreSQL es de la edición comercial; el beneficio no compensa para un solo desarrollador |
| **Spring Data JDBC** | Más simple que JPA, pero su modelo de agregado (repositorios por raíz, sin consultas condicionales ni bloqueo explícito) no cubre las necesidades de concurrencia de arriba sin volver a `@Query` nativo |

## Consecuencias

- **Positivas:** el SQL que se ejecuta es el que está escrito y el que se prueba; la concurrencia y los lotes se ven en el código; sin reflexión ni proxies de entidades (compatible con GraalVM Native, ADR-0014); el dominio sigue sin anotaciones.
- **Costos asumidos:**
  - más código repetitivo de mapeo `ResultSet` → registro;
  - un cambio de esquema exige revisar a mano las sentencias que tocan la tabla;
  - sin comprobación de tipos del SQL al compilar.
- **Mitigación de los costos:** las pruebas de integración por adaptador; las convenciones de migración y squawk (ADR-0027); las pruebas que cuentan sentencias en las lecturas por lotes; y el análisis de Sonar, que marca el SQL armado dinámicamente.
- **Documentos que se corrigen con este ADR:** `test-strategy.md`, `coding-standards.md`, `data-model.md` y `domain-model.md`, que describían el mapeo JPA. Las verificaciones de Hibernate de la revisión de noviembre (`09-…`, anexos) se conservan como registro de lo que se probó; ya no describen la implementación.

## Revisión técnica (27-nov-2026)

Revisión con el rol de SDE III senior, asistida por IA y a petición del responsable del proyecto. **Limitación:** quien revisa es quien redactó el ADR; no sustituye la revisión de una persona ajena al proyecto.

- **El ADR registra, no cambia:** no hay código que migrar. Se comprobó que ningún `pom.xml` declara JPA ni Hibernate y que no hay ninguna `@Entity`.
- **Los motivos se sostienen en el código actual:** las cuatro correcciones de concurrencia de noviembre (testigo de propiedad en la bandeja y la cola, *upsert* ordenado con el cálculo más reciente, lápida `FOR SHARE`) son sentencias únicas que un ORM obligaría a escribir igualmente como SQL nativo.
- **El riesgo principal es el costo de mantener SQL a mano.** Se acepta porque cada adaptador tiene prueba contra PostgreSQL real y el análisis de Sonar marca el SQL armado en tiempo de ejecución (lo hizo en el PR #53).
- **Condición:** la regla 2 (SQL constante) se revisa en cada PR. Una consulta que necesite filtros opcionales combinables, como una búsqueda futura (ADR-0023), deberá resolverse sin concatenar fragmentos (por ejemplo, con condiciones `(:x is null or columna = :x)`) o reabrir este ADR.
