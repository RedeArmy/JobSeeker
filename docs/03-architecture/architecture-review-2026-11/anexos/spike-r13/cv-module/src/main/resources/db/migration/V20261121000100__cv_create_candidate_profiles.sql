-- ADR-0027: tabla del contexto `cv`; depende de la extensión creada por V1 de `app`.
create table candidate_profiles (
    id uuid primary key,
    discoverable boolean not null default false,
    embedding vector(1024),
    embedding_model text,
    embedding_dim integer,
    embedding_content_hash char(64),
    constraint ck_cp_embedding_complete check (
        (embedding is null and embedding_model is null and embedding_dim is null and embedding_content_hash is null)
        or (embedding is not null and embedding_model is not null and embedding_dim is not null and embedding_content_hash is not null))
);
create index idx_cp_embedding_hnsw on candidate_profiles using hnsw (embedding vector_cosine_ops)
    where discoverable and embedding is not null;
