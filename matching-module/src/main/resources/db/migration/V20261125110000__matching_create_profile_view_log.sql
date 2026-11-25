-- Registro de los perfiles que un reclutador vio por los sugeridos (REQ-R-11): es lo que cuenta la cuota
-- de perfiles únicos por ventana de 24 h y lo que permite reconstruir qué vio quién si hay un abuso
-- (amenaza T-N1, raspado de datos de candidatos). Es de `matching`, que es quien lo escribe (ADR-0027).
--
-- Sin claves foráneas hacia `accounts`, `candidate_profiles` ni `job_postings`, igual que `audit_log`: el
-- historial debe sobrevivir a la anonimización de la cuenta (BR-DATA-05) y a la baja de la vacante
-- (BR-JOB-09). No guarda ningún dato personal: solo identificadores opacos y el momento.
create table profile_view_log (
    id uuid primary key,
    viewer_account_id uuid not null,
    candidate_profile_id uuid not null,
    job_posting_id uuid not null,
    -- Desde dónde se vio; hoy solo los sugeridos de una vacante (BR-MATCH-06).
    context text not null,
    viewed_at timestamptz not null default now(),
    constraint ck_pvl_context check (context in ('SUGGESTION'))
);

-- La cuota recorre los perfiles que un reclutador vio dentro de la ventana, así que el índice empieza por
-- quien mira y sigue por el momento.
create index idx_pvl_viewer on profile_view_log (viewer_account_id, viewed_at);
