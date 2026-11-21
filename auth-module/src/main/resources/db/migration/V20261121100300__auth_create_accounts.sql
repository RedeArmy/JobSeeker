-- Espejo local de la identidad de Clavaris (data-model.md §4.1): `id` ES el `sub` del token, nunca
-- un identificador generado aquí. No hay correo, contraseña ni sesiones: pertenecen a Clavaris.
-- BR-ACCT-01: las capacidades de candidato y de reclutador son independientes y no excluyentes (ADR-0008).
create table accounts (
    id uuid primary key,
    is_candidate_enabled boolean not null default false,
    is_recruiter_enabled boolean not null default false,
    -- Ciclo de vida de eliminación (BR-DATA-04/05, ADR-0013): periodo de gracia y luego anonimización.
    deletion_status text not null default 'ACTIVE',
    deletion_requested_at timestamptz,
    -- deletion_requested_at + accountDeletionGracePeriodDays: el lote de anonimización recorre esta columna.
    scheduled_anonymization_at timestamptz,
    anonymized_at timestamptz,
    created_at timestamptz not null default now(),
    constraint ck_accounts_capability check (is_candidate_enabled or is_recruiter_enabled),
    constraint ck_accounts_deletion_status check (deletion_status in ('ACTIVE', 'PENDING_DELETION', 'ANONYMIZED'))
);

-- El lote de anonimización solo recorre las solicitudes vencidas.
create index idx_accounts_scheduled_anonymization on accounts (scheduled_anonymization_at)
    where deletion_status = 'PENDING_DELETION';
