-- Registro de auditoría de acciones relevantes. `details` nunca lleva PII (BR-DATA-01), y por eso
-- se conserva con el `actor_account_id` tras anonimizar la cuenta (BR-DATA-05).
-- Sin clave foránea a `accounts`: el historial debe sobrevivir a la anonimización de la cuenta.
create table audit_log (
    id uuid primary key,
    at timestamptz not null default now(),
    actor_account_id uuid not null,
    organization_id uuid,
    action text not null,
    target_type text not null,
    target_id uuid,
    trace_id text,
    details jsonb
);

create index idx_audit_actor_at on audit_log (actor_account_id, at);
create index idx_audit_target on audit_log (target_type, target_id);
