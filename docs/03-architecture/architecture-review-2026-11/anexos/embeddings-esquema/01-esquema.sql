-- Esquema de embeddings versionados (ADR-0018, data-model.md §4.4, §4.6 y §5.1).
-- Probado el 18-nov-2026 en PostgreSQL 16.16 + pgvector 0.8.7 (imagen pgvector/pgvector:pg16).
-- Es punto de partida de la migración Flyway del Sprint 0, no la migración final: se recortan
-- aquí las columnas ajenas a los embeddings.

CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE candidate_profiles (
  id                       uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  account_id               uuid NOT NULL UNIQUE,
  discoverable             boolean NOT NULL DEFAULT true,
  country_of_residence     varchar,
  -- La dimensión es fija por columna: el índice HNSW la exige. 1024 = qwen3-embedding:0.6b (provisional).
  embedding                vector(1024),
  embedding_model          varchar,
  -- Columna normal y no GENERATED: añadir una columna generada almacenada reescribe la tabla.
  embedding_dim            integer,
  embedding_content_hash   char(64),
  embedding_truncated      boolean NOT NULL DEFAULT false,
  embedding_computed_at    timestamptz,
  -- El bloque es todo o nada: un vector nunca queda sin saber con qué modelo se calculó,
  -- y la anonimización (BR-DATA-05) debe anular el bloque completo a la vez.
  CONSTRAINT ck_cp_embedding_complete CHECK (
    (embedding IS NULL AND embedding_model IS NULL AND embedding_dim IS NULL
       AND embedding_content_hash IS NULL AND embedding_computed_at IS NULL)
    OR (embedding IS NOT NULL AND embedding_model IS NOT NULL AND embedding_dim = vector_dims(embedding)
       AND embedding_content_hash IS NOT NULL AND embedding_computed_at IS NOT NULL))
);

CREATE TABLE job_postings (
  id                       uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  status                   varchar NOT NULL DEFAULT 'DRAFT'
                             CHECK (status IN ('DRAFT','PUBLISHED','CLOSED','ARCHIVED','DELETED')),
  country                  varchar,
  embedding                vector(1024),
  embedding_model          varchar,
  embedding_dim            integer,
  embedding_content_hash   char(64),
  embedding_truncated      boolean NOT NULL DEFAULT false,
  embedding_computed_at    timestamptz,
  CONSTRAINT ck_jp_embedding_complete CHECK (
    (embedding IS NULL AND embedding_model IS NULL AND embedding_dim IS NULL
       AND embedding_content_hash IS NULL AND embedding_computed_at IS NULL)
    OR (embedding IS NOT NULL AND embedding_model IS NOT NULL AND embedding_dim = vector_dims(embedding)
       AND embedding_content_hash IS NOT NULL AND embedding_computed_at IS NOT NULL))
);

-- Índices parciales: solo lo que puede puntuarse (candidatos descubribles, vacantes publicadas).
-- Medido con 10.000 candidatos: top-20 con HNSW, mediana 4,6 ms, p95 10 ms.
CREATE INDEX idx_cp_embedding_hnsw ON candidate_profiles USING hnsw (embedding vector_cosine_ops)
  WHERE discoverable AND embedding IS NOT NULL;
CREATE INDEX idx_jp_embedding_hnsw ON job_postings USING hnsw (embedding vector_cosine_ops)
  WHERE status = 'PUBLISHED' AND embedding IS NOT NULL;
-- Para contar y localizar los vectores que no son del modelo vigente durante un cambio de modelo.
CREATE INDEX idx_cp_embedding_model ON candidate_profiles (embedding_model) WHERE embedding IS NOT NULL;

-- Consulta de similitud: el vector debe llegar como parámetro o literal (no como columna de otra
-- tabla unida, o el planificador no usa el índice) y con iterative_scan activo.
--   SET hnsw.iterative_scan = relaxed_order;
--   SELECT id FROM candidate_profiles
--    WHERE discoverable AND embedding IS NOT NULL
--    ORDER BY embedding <=> :query LIMIT 20;
