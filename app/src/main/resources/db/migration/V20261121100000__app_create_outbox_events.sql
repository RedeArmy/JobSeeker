-- ADR-0016: bandeja de salida transaccional. El caso de uso inserta aquí el evento en la misma
-- transacción que el cambio de dominio; un sondeador alojado en `app` lo despacha (SKIP LOCKED).
-- Solo identificadores y metadatos, nunca PII (BR-DATA-01).
create table outbox_events (
    id uuid primary key,
    aggregate_type text not null,
    aggregate_id uuid not null,
    -- Orden por agregado (ADR-0016 decisión 4): no hay orden global garantizado.
    aggregate_version bigint not null,
    event_type text not null,
    event_version integer not null default 1,
    payload jsonb not null,
    trace_id text,
    status text not null default 'PENDING',
    attempts integer not null default 0,
    created_at timestamptz not null default now(),
    -- Reintento con retroceso exponencial: una fila fallida vuelve a estar disponible más tarde.
    available_at timestamptz not null default now(),
    processed_at timestamptz,
    constraint ck_outbox_status check (status in ('PENDING', 'PROCESSED', 'FAILED')),
    constraint ck_outbox_processed check ((status = 'PROCESSED') = (processed_at is not null))
);

-- El sondeador solo mira lo pendiente: el índice parcial se mantiene pequeño aunque la tabla crezca.
create index idx_outbox_pending on outbox_events (available_at) where status = 'PENDING';
create index idx_outbox_aggregate on outbox_events (aggregate_type, aggregate_id, aggregate_version);
-- Purga de filas procesadas a los 7 días (ADR-0016 decisión 8).
create index idx_outbox_processed_at on outbox_events (processed_at) where status = 'PROCESSED';

-- Deduplicación de consumidores idempotentes (ADR-0016 decisiones 3 y 11): se conserva al menos 30
-- días, más que cualquier ventana de reintento o reenvío de un emisor externo.
create table processed_events (
    consumer text not null,
    event_id uuid not null,
    processed_at timestamptz not null default now(),
    primary key (consumer, event_id)
);

create index idx_processed_events_processed_at on processed_events (processed_at);
