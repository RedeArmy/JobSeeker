-- Vectores de perfiles y vacantes (data-model.md §4.7.1). Son de `matching`, que es quien los calcula y
-- los escribe (ADR-0027: cada contexto es dueño de sus tablas); antes vivían en las tablas de `cv` y de
-- `job`. Requiere la extensión `vector`, que crea la V1 de `app`.
-- No hay clave foránea hacia `cv` ni `job`: la consistencia la dan los eventos y la anonimización.

create table profile_embeddings (
    candidate_profile_id uuid primary key,
    -- COPIA de candidate_profiles.discoverable (BR-DATA-03): el índice parcial de abajo solo puede
    -- nombrar columnas de su propia tabla. Falso por defecto: ante la duda, no es descubrible.
    discoverable boolean not null default false,
    -- La dimensión es fija por columna (la exige el índice HNSW): 1024 con el modelo provisional del
    -- ADR-0018. Un cambio de modelo usa el procedimiento de expansión de data-model.md §5.1.
    embedding vector(1024),
    embedding_model text,
    embedding_dim integer,
    -- SHA-256 en hexadecimal de la representación canónica: si no cambia, no se llama a Ollama.
    embedding_content_hash char(64),
    embedding_truncated boolean not null default false,
    embedding_computed_at timestamptz,
    updated_at timestamptz not null default now(),
    -- Todo o nada: un vector nunca queda sin saber con qué modelo se calculó.
    constraint ck_pe_embedding_complete check (
        (embedding is null and embedding_model is null and embedding_dim is null
            and embedding_content_hash is null and embedding_computed_at is null)
        or (embedding is not null and embedding_model is not null and embedding_dim is not null
            and embedding_content_hash is not null and embedding_computed_at is not null
            and embedding_dim = vector_dims(embedding)))
);

-- Búsqueda de candidatos sugeridos (BR-MATCH-06): solo perfiles descubribles y con vector (BR-VIS-04),
-- así un perfil anonimizado, que queda con descubribilidad falsa, nunca vuelve a aparecer.
create index idx_pe_embedding_hnsw on profile_embeddings using hnsw (embedding vector_cosine_ops)
    where discoverable and embedding is not null;

-- Contar y localizar los vectores que no son del modelo vigente durante un cambio de modelo.
create index idx_pe_embedding_model on profile_embeddings (embedding_model) where embedding is not null;

create table job_embeddings (
    job_posting_id uuid primary key,
    -- COPIA de job_postings.status = 'PUBLISHED', por la misma razón: una vacante que no está
    -- publicada nunca es candidata a puntuarse (BR-JOB-06, BR-JOB-11).
    published boolean not null default false,
    embedding vector(1024),
    embedding_model text,
    embedding_dim integer,
    embedding_content_hash char(64),
    embedding_truncated boolean not null default false,
    embedding_computed_at timestamptz,
    updated_at timestamptz not null default now(),
    constraint ck_je_embedding_complete check (
        (embedding is null and embedding_model is null and embedding_dim is null
            and embedding_content_hash is null and embedding_computed_at is null)
        or (embedding is not null and embedding_model is not null and embedding_dim is not null
            and embedding_content_hash is not null and embedding_computed_at is not null
            and embedding_dim = vector_dims(embedding)))
);

create index idx_je_embedding_hnsw on job_embeddings using hnsw (embedding vector_cosine_ops)
    where published and embedding is not null;

create index idx_je_embedding_model on job_embeddings (embedding_model) where embedding is not null;
