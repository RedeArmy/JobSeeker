-- Postulaciones y su historial de estados (tdd/ats-tracker-modules.md §7, cambios A1 a A4).
-- Sin datos personales (regla 7 del TDD): solo identificadores, estados, fechas y un motivo codificado.
-- El nombre y el contacto se piden a `cv` al mostrar, así que anonimizar no toca estas tablas (BR-DATA-05).
-- Las reglas del dominio se repiten como CHECK: la base es la última defensa, no la única.

create table applications (
    id uuid primary key,
    candidate_profile_id uuid not null references candidate_profiles (id),
    -- La cuenta además del perfil (A4): el tracker y la autorización del candidato trabajan con la
    -- cuenta del token, sin preguntar a `cv` cuál es su perfil en cada lectura.
    candidate_account_id uuid not null references accounts (id),
    job_posting_id uuid not null references job_postings (id),
    -- La versión del CV con que se postuló, no la vigente: subir otra después no cambia lo que el
    -- reclutador recibió (ADR-0021 §5). Las versiones nunca se borran físicamente, solo se marcan.
    cv_file_id uuid not null references candidate_cv_files (id),
    status text not null,
    -- La columna pública del tablero (D1): catálogo fijo en la Ola 1 (D2).
    stage text not null,
    -- Motivo interno del descarte (D5); el candidato nunca lo ve (BR-TRACK-03).
    rejection_reason text,
    -- Bloqueo optimista y compare-and-set de toda transición: la primera versión es 1, como en `job`.
    version bigint not null,
    applied_at timestamptz not null,
    updated_at timestamptz not null,
    constraint ck_app_status check (status in ('ACTIVE', 'HIRED', 'REJECTED', 'WITHDRAWN')),
    constraint ck_app_stage check (stage in ('NEW', 'REVIEW', 'INTERVIEW', 'OFFER')),
    constraint ck_app_rejection_reason check (rejection_reason is null
        or rejection_reason in ('NOT_A_FIT', 'POSITION_FILLED', 'CANDIDATE_UNRESPONSIVE', 'OTHER')),
    -- Solo una descartada tiene motivo, y toda descartada lo tiene (REQ-R-03): reabrir lo quita (D3).
    constraint ck_app_rejection check ((status = 'REJECTED') = (rejection_reason is not null)),
    constraint ck_app_version check (version >= 1),
    constraint ck_app_dates check (updated_at >= applied_at)
);

-- BR-TRACK-01 y BR-TRACK-05: una sola postulación por candidato y vacante, salvo las retiradas
-- (REQ-C-03 deja volver a postular tras retirarse). Una descartada o contratada sigue contando: el
-- candidato no puede crear otra, y reabrir una descartada reutiliza la misma fila (D3). Lo impone la
-- base y no una lectura previa: de dos envíos simultáneos, uno solo gana (regla 4 del TDD).
create unique index uq_app_one_per_job on applications (candidate_profile_id, job_posting_id)
    where status <> 'WITHDRAWN';
-- Pipeline de una vacante filtrado por estado (NFR-PERF-02).
create index idx_app_job on applications (job_posting_id, status);
-- Tracker del candidato, de la más reciente a la más antigua.
create index idx_app_candidate on applications (candidate_account_id, applied_at desc);

-- Cada cambio de estado o de etapa (A2): lo que el candidato ve como historial (BR-TRACK-02) y lo que
-- hace auditable un descarte (REQ-R-03). Solo se insertan filas, nunca se modifican.
create table application_status_history (
    id uuid primary key,
    application_id uuid not null references applications (id),
    -- Nulos solo en la primera fila: la postulación no existía antes de postular.
    from_status text,
    to_status text not null,
    from_stage text,
    to_stage text not null,
    actor text not null,
    -- El motivo del descarte se conserva aquí aunque reabrir lo quite de la postulación (D5).
    rejection_reason text,
    changed_at timestamptz not null,
    constraint ck_ash_from_status check (from_status is null
        or from_status in ('ACTIVE', 'HIRED', 'REJECTED', 'WITHDRAWN')),
    constraint ck_ash_to_status check (to_status in ('ACTIVE', 'HIRED', 'REJECTED', 'WITHDRAWN')),
    constraint ck_ash_from_stage check (from_stage is null
        or from_stage in ('NEW', 'REVIEW', 'INTERVIEW', 'OFFER')),
    constraint ck_ash_to_stage check (to_stage in ('NEW', 'REVIEW', 'INTERVIEW', 'OFFER')),
    constraint ck_ash_actor check (actor in ('CANDIDATE', 'RECRUITER', 'SYSTEM')),
    constraint ck_ash_rejection_reason check (rejection_reason is null
        or rejection_reason in ('NOT_A_FIT', 'POSITION_FILLED', 'CANDIDATE_UNRESPONSIVE', 'OTHER')),
    -- El origen viene completo o no viene: un cambio con estado de origen tiene también su etapa.
    constraint ck_ash_from check ((from_status is null) = (from_stage is null))
);

-- El historial de una postulación en orden.
create index idx_ash_application on application_status_history (application_id, changed_at);
