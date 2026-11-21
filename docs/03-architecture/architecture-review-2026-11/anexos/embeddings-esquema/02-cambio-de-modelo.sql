-- Procedimiento de cambio de modelo de embeddings (ADR-0018, data-model.md §5.1).
-- Probado el 18-nov-2026: 1024 -> 768 dimensiones, 10.000 filas. Tiempos entre paréntesis.
-- Nunca se hace ALTER COLUMN TYPE sobre un vector en uso.

-- 1. EXPANDIR (migración Flyway; solo metadatos, sin reescribir la tabla) ................ (0,44 s)
ALTER TABLE candidate_profiles
  ADD COLUMN embedding_next              vector(768),
  ADD COLUMN embedding_next_model        varchar,
  ADD COLUMN embedding_next_dim          integer,
  ADD COLUMN embedding_next_content_hash char(64),
  ADD COLUMN embedding_next_truncated    boolean NOT NULL DEFAULT false,
  ADD COLUMN embedding_next_computed_at  timestamptz;
ALTER TABLE candidate_profiles ADD CONSTRAINT ck_cp_embedding_next_complete CHECK (
  (embedding_next IS NULL AND embedding_next_model IS NULL AND embedding_next_dim IS NULL
     AND embedding_next_content_hash IS NULL AND embedding_next_computed_at IS NULL)
  OR (embedding_next IS NOT NULL AND embedding_next_model IS NOT NULL
     AND embedding_next_dim = vector_dims(embedding_next)
     AND embedding_next_content_hash IS NOT NULL AND embedding_next_computed_at IS NOT NULL));

-- 2. DOBLE ESCRITURA + RELLENO (aplicación): el consumidor escribe ambos slots; un job por
--    lotes rellena las filas con embedding_next IS NULL ........................ (31 s / 10.000 filas)
--    UPDATE candidate_profiles SET embedding_next = :v, embedding_next_model = :m, ...
--     WHERE id = :id AND embedding_next_content_hash IS DISTINCT FROM :hash;

-- 3. ÍNDICE del slot siguiente, sin bloquear escrituras (fuera de transacción) ........... (22 s)
CREATE INDEX CONCURRENTLY idx_cp_embedding_next_hnsw ON candidate_profiles
  USING hnsw (embedding_next vector_cosine_ops) WHERE discoverable AND embedding_next IS NOT NULL;

-- 4. SOMBRA Y CALIBRACIÓN (aplicación): puntuar con el slot siguiente en paralelo, ajustar la
--    calibración del modelo nuevo con pares etiquetados y comparar la precisión.

-- 5. CAMBIO: una sola transacción, solo metadatos ......................................... (1,3 s)
--    Debe salir junto con la versión de la aplicación (la longitud va en @Array de la entidad).
BEGIN;
SET LOCAL lock_timeout = '5s';
DROP INDEX idx_cp_embedding_hnsw;
ALTER TABLE candidate_profiles
  DROP COLUMN embedding, DROP COLUMN embedding_model, DROP COLUMN embedding_dim,
  DROP COLUMN embedding_content_hash, DROP COLUMN embedding_truncated, DROP COLUMN embedding_computed_at;
ALTER TABLE candidate_profiles RENAME COLUMN embedding_next TO embedding;
ALTER TABLE candidate_profiles RENAME COLUMN embedding_next_model TO embedding_model;
ALTER TABLE candidate_profiles RENAME COLUMN embedding_next_dim TO embedding_dim;
ALTER TABLE candidate_profiles RENAME COLUMN embedding_next_content_hash TO embedding_content_hash;
ALTER TABLE candidate_profiles RENAME COLUMN embedding_next_truncated TO embedding_truncated;
ALTER TABLE candidate_profiles RENAME COLUMN embedding_next_computed_at TO embedding_computed_at;
ALTER TABLE candidate_profiles RENAME CONSTRAINT ck_cp_embedding_next_complete TO ck_cp_embedding_complete;
ALTER INDEX idx_cp_embedding_next_hnsw RENAME TO idx_cp_embedding_hnsw;
COMMIT;
-- Verificado tras el cambio: tipo vector(768), un insert de 1024 dimensiones se rechaza
-- ("expected 768 dimensions, not 1024") y la consulta de producción usa el índice renombrado.

-- 6. NUEVA algorithm_version (aplicación): incluye el modelo nuevo; los scores anteriores se
--    conservan como histórico (BR-MATCH-04).
