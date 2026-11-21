-- Idempotencia de los POST que crean recursos (postular, invitar, solicitud de seguimiento): la
-- misma clave de la misma cuenta devuelve la respuesta guardada en vez de repetir el efecto.
-- Sin clave foránea a `accounts`: esta tabla es de infraestructura y no debe acoplarse al contexto auth.
create table idempotency_keys (
    account_id uuid not null,
    key text not null,
    -- Huella de la petición: la misma clave con un cuerpo distinto es un error del cliente. Es un
    -- SHA-256 en hexadecimal, siempre de 64 caracteres: char(64) es exacto, sin relleno.
    -- squawk-ignore ban-char-field
    request_hash char(64) not null,
    response jsonb,
    created_at timestamptz not null default now(),
    primary key (account_id, key)
);

-- Purga de claves viejas.
create index idx_idempotency_created_at on idempotency_keys (created_at);
