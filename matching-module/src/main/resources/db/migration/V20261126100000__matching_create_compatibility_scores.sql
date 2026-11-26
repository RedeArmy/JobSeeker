-- Persistencia del puntaje de compatibilidad (ADR-0017, decisión 9; data-model.md §4.7). Son de
-- `matching`, que es quien las escribe (ADR-0027). Sin claves foráneas hacia `cv` ni `job`, como el
-- resto de tablas de este contexto: la consistencia la dan los eventos y la anonimización.
--
-- NUNCA se materializan todos los pares: solo los «con motivo» (los sugeridos que un reclutador vio,
-- los que un candidato consultó y los postulados). El resto se calcula al pedirlo, con aritmética.

-- El puntaje vigente de un par, con `upsert`: reemplaza el histórico por recálculo del diseño inicial.
create table compatibility_scores_current (
    candidate_profile_id uuid not null,
    job_posting_id uuid not null,
    overall numeric(5, 2) not null,
    completeness numeric(4, 3) not null,
    calibrated boolean not null,
    -- Versión completa del algoritmo (código, modelo, hash de pesos y de calibración): BR-MATCH-04.
    algorithm_version text not null,
    -- El desglose por dimensión con sus explicaciones. En una cuenta anonimizada pasa a `{}`: lo que
    -- queda es el metadato (par, puntaje, versión), no el contenido derivado del perfil (BR-DATA-05).
    breakdown jsonb not null,
    -- Por qué existe el par. Solo ordena el recálculo y la retención; un par postulado nunca baja de
    -- motivo (ver el `upsert` del adaptador).
    reason text not null,
    computed_at timestamptz not null default now(),
    anonymized_at timestamptz,
    primary key (candidate_profile_id, job_posting_id),
    constraint ck_csc_overall check (overall between 0 and 100),
    constraint ck_csc_completeness check (completeness between 0 and 1),
    constraint ck_csc_reason check (reason in ('SUGGESTED', 'CANDIDATE_VIEW', 'APPLIED'))
);

-- Recalcular los pares de una vacante cuando cambia, y buscar los de una versión vieja del algoritmo.
create index idx_csc_job on compatibility_scores_current (job_posting_id);
create index idx_csc_version on compatibility_scores_current (algorithm_version, computed_at);

-- Fotos del puntaje: al postular, al cambiar de etapa, al cambiar `algorithm_version` y en muestras.
-- Permiten comparar versiones y reconstruir qué vio un reclutador. Retención de 24 meses.
create table compatibility_score_snapshots (
    id uuid primary key,
    candidate_profile_id uuid not null,
    job_posting_id uuid not null,
    reason text not null,
    overall numeric(5, 2) not null,
    completeness numeric(4, 3) not null,
    calibrated boolean not null,
    algorithm_version text not null,
    breakdown jsonb not null,
    created_at timestamptz not null default now(),
    anonymized_at timestamptz,
    constraint ck_css_overall check (overall between 0 and 100),
    constraint ck_css_reason check (reason in ('APPLIED', 'STAGE_CHANGED', 'VERSION_CHANGED', 'SAMPLE'))
);

create index idx_css_pair on compatibility_score_snapshots (candidate_profile_id, job_posting_id, created_at);
-- La purga por retención recorre por fecha.
create index idx_css_created on compatibility_score_snapshots (created_at);

-- La copia inmutable del perfil canónico al postular: el reclutador evalúa lo que el candidato
-- presentó, no lo que edite después (ADR-0017, F6). Su texto libre es un dato derivado de la persona
-- aunque se excluyan nombre y contacto, así que al anonimizar la cuenta `canonical_text` y
-- `canonical_hash` pasan a NULL y la fila queda como metadato.
create table application_profile_snapshots (
    id uuid primary key,
    candidate_profile_id uuid not null,
    job_posting_id uuid not null,
    -- Identificador opaco de la postulación en `ats` (que aún no existe); nulo hasta entonces.
    application_id uuid,
    canonical_text text,
    canonical_hash text,
    algorithm_version text not null,
    created_at timestamptz not null default now(),
    anonymized_at timestamptz,
    constraint ck_aps_hash check (canonical_hash is null or canonical_hash ~ '^[0-9a-f]{64}$')
);

create index idx_aps_pair on application_profile_snapshots (candidate_profile_id, job_posting_id);
-- Una postulación tiene una sola copia.
create unique index uq_aps_application on application_profile_snapshots (application_id)
    where application_id is not null;
