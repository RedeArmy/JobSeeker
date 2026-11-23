-- ADR-0020 (superficie C): espejo local del estado de la identidad en Clavaris, que alimentan sus
-- webhooks. No es la fuente de verdad (un webhook puede perderse): sirve para ordenar los eventos
-- que llegan y para conciliar después contra Clavaris.
--
-- `identity_event_at` + `identity_event_id` son la tupla `(occurredAt, event_id)` del último evento
-- aplicado: comparar solo `occurredAt` dejó 7 de 30 cuentas en un estado que depende del orden de
-- llegada cuando hay un empate exacto (spike, addendum 4); la tupla lo hace determinista.
--
-- Excepción de squawk (constraint-missing-not-valid), y es segura: `accounts` la creó la migración
-- anterior del mismo sprint y aún no existe en ningún entorno desplegado, así que no hay filas que
-- escanear ni escrituras que bloquear. Partirla en `not valid` + una migración de `validate` solo
-- añadiría un archivo sin reducir ningún riesgo.
alter table accounts
    add column identity_status text not null default 'ACTIVE',
    add column identity_event_at timestamptz,
    add column identity_event_id uuid,
    -- squawk-ignore constraint-missing-not-valid
    add constraint ck_accounts_identity_status
        check (identity_status in ('ACTIVE', 'SUSPENDED', 'DELETED')),
    -- La marca de tiempo y el identificador del último evento van siempre juntos.
    -- squawk-ignore constraint-missing-not-valid
    add constraint ck_accounts_identity_event
        check ((identity_event_at is null) = (identity_event_id is null));
