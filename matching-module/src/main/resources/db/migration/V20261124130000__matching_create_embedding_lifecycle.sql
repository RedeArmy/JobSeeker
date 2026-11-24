-- Ciclo de vida de los embeddings de perfiles (TDD de matching, sub-paso 3; ADR-0017 decisiones 2 y 10).
--
-- `profile_embedding_accounts` liga la cuenta con su perfil de candidato **dentro de matching**. Los
-- eventos de `cv` traen la cuenta, pero `profile_embeddings` se indexa por perfil, y cuando el perfil
-- deja de ser legible (anonimizado, capacidad apagada) `cv` ya no puede decir cuál era. Sin clave
-- foránea hacia otro contexto. `anonymized_at` es una lápida: una vez puesta, ninguna escritura de
-- un cálculo que ya estaba en curso puede volver a dejar un vector de esa cuenta (BR-DATA-05).
--
-- `matching_work_queue` es la cola de trabajos de embedding. Una fila por entidad (única), de modo
-- que varias ediciones seguidas se agrupan en un trabajo: `generation` sube con cada petición y quien
-- termina solo borra la fila si no llegó otra mientras trabajaba. Se reclama con arrendamiento y se
-- trabaja sin transacción abierta, como la bandeja (ADR-0016, enmienda del 24-nov-2026); un trabajo
-- que falla se reintenta con retroceso y, agotados los intentos, queda en FAILED para revisión.
-- Sin PII: la clave es el identificador de la cuenta.
create table profile_embedding_accounts (
    account_id uuid primary key,
    -- Nulo solo en una lápida de una cuenta cuyo perfil `matching` nunca llegó a conocer.
    candidate_profile_id uuid unique,
    created_at timestamptz not null default now(),
    anonymized_at timestamptz
);

create table matching_work_queue (
    id uuid primary key,
    entity_type text not null,
    entity_key uuid not null,
    -- Cuándo puede empezar: la ventana de agrupación de ediciones (ADR-0017 decisión 10).
    not_before timestamptz not null default now(),
    -- Desde cuándo hay una petición sin atender: lo que mide la frescura (ADR-0017, C4). Se conserva la
    -- más antigua al agrupar ediciones y se reinicia al liberar el trabajo para una petición posterior.
    pending_since timestamptz not null default now(),
    generation bigint not null default 1,
    locked_until timestamptz,
    attempts integer not null default 0,
    status text not null default 'PENDING',
    created_at timestamptz not null default now(),
    constraint uq_mwq_entity unique (entity_type, entity_key),
    constraint ck_mwq_status check (status in ('PENDING', 'FAILED')),
    constraint ck_mwq_entity_type check (entity_type in ('PROFILE'))
);

-- El trabajador solo mira lo pendiente: el índice parcial se mantiene pequeño.
create index idx_mwq_pending on matching_work_queue (not_before) where status = 'PENDING';
