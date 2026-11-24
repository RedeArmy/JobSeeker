-- Anonimización de los datos de `cv` de una cuenta (data-model.md §4.2, §4.4 y §4.10, BR-DATA-05,
-- ADR-0013). `anonymized_at` marca el estado terminal de cada perfil: sirve de marca de idempotencia
-- (anonimizar dos veces no hace nada) y hace que la aplicación trate un perfil anonimizado como
-- inexistente, sin depender de comparar el nombre con el marcador.
--
-- En el perfil de candidato el correo deja de ser obligatorio *solo* si el perfil está anonimizado, y
-- la propia restricción exige que un perfil anonimizado no conserve contacto ni sea descubrible: la
-- base es la última defensa de que nunca vuelva a aparecer en sugerencias.
--
-- Excepción de squawk (constraint-missing-not-valid), y es segura: estas tablas las crearon
-- migraciones de hace pocos sprints y aún no existen en ningún entorno desplegado, así que no hay
-- filas que escanear ni escrituras que bloquear. Es la misma razón de V20261122100000.
alter table professional_profiles
    add column anonymized_at timestamptz,
    -- squawk-ignore constraint-missing-not-valid
    add constraint ck_pp_anonymized
        check (anonymized_at is null or (headline is null and bio is null and profile_photo_url is null));

alter table candidate_profiles
    add column anonymized_at timestamptz,
    alter column contact_email drop not null,
    -- squawk-ignore constraint-missing-not-valid
    add constraint ck_cp_anonymized
        check (
            (anonymized_at is null and contact_email is not null)
            or (anonymized_at is not null and contact_email is null and contact_phone is null
                and not discoverable)
        );
