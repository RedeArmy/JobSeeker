# Esquema de embeddings versionados (ADR-0018)

Probado el **18-nov-2026** en un contenedor aislado `pgvector/pgvector:pg16` (PostgreSQL 16.16, pgvector 0.8.7), con 10.000 candidatos de 1024 dimensiones y 2.000 vacantes de datos aleatorios. El contenedor se eliminó al terminar. El recall se repitió el 20-nov-2026 sobre PostgreSQL 17.12 con el mismo resultado (`recall/README.md`, ADR-0028); los tiempos de DDL de este anexo no se repitieron.

- `01-esquema.sql`: tablas, restricción todo-o-nada del bloque `embedding*` e índices HNSW parciales.
- `02-cambio-de-modelo.sql`: procedimiento de seis pasos con los tiempos medidos (1024 → 768 dimensiones).

## Qué se verificó

| Comprobación | Resultado |
|---|---|
| Restricción del bloque (vector sin modelo; anular solo el vector) | Rechazadas por `ck_cp_embedding_complete` |
| Inserto con dimensión distinta a la de la columna | `expected 1024 dimensions, not 768` |
| Anonimización: anular el bloque completo a la vez | Aceptada |
| Top-20 de descubribles | Índice HNSW; mediana 4,6 ms, p95 10 ms |
| Con filtro selectivo de país | Escaneo exacto; mediana 16 ms, p95 20 ms |
| Cambio de modelo (pasos 1 a 5) | 0,44 s + 31 s + 22 s + 1,3 s; el índice renombrado se sigue usando |

## Hallazgos que cambiaron el diseño

- Una columna `GENERATED ALWAYS ... STORED` para `embedding_dim` obliga a reescribir toda la tabla al añadirla: se usa una columna normal con restricción.
- Con el vector de consulta como columna de otra tabla unida, el planificador **no** usa el índice (escaneo secuencial, ~128 ms); con parámetro o literal sí (~5 ms).
- Un primer intento de relleno falló en silencio por mala sintaxis (`LATERAL` en un `UPDATE`) y dejó todas las filas pendientes: el procedimiento debe comprobar que no quedan filas con `embedding_next IS NULL` antes del cambio.

## Verificado después con Hibernate, Spring AI y un *pooler* emulado

Ver `../../09-verificacion-supabase-hibernate-despliegue.md` y `03-mapeo-jpa.md`: el mapeo de dos slots funciona; la validación de esquema no comprueba la longitud del vector; cambiar columnas rompe a las instancias viejas (24 correctas y 104 fallidas); `num_ctx` global de Spring AI no se envía.

## No verificado

El recall con vectores reales se midió después: `recall/README.md`. Aun así, falta un corpus real de miles de perfiles distintos.

PostgreSQL gestionado de Supabase (versión de pgvector, `hnsw.iterative_scan`), vectores reales de un modelo (los de la prueba son aleatorios, así que la calidad de la búsqueda no se midió, solo el comportamiento del esquema), el mapeo con Hibernate de ambos slots y el comportamiento con más de una instancia durante el cambio.
