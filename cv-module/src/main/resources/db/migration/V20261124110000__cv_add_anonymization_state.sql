-- Anonimización de los datos de `cv` de una cuenta (data-model.md §4.2, §4.4 y §4.10, BR-DATA-05,
-- ADR-0013). `anonymized_at` marca el estado terminal de cada perfil: sirve de marca de idempotencia
-- (anonimizar dos veces no hace nada) y hace que la aplicación trate un perfil anonimizado como
-- inexistente, sin depender de comparar el nombre con el marcador.
--
-- En el perfil de candidato el correo deja de ser obligatorio *solo* si el perfil está anonimizado, y
-- la propia restricción exige que un perfil anonimizado no conserve contacto, expectativa salarial ni
-- disponibilidad, ni sea descubrible: la base es la última defensa de que nunca vuelva a aparecer en
-- sugerencias ni conserve un dato sensible que nadie lee.
--
-- Excepciones de squawk. `ban-drop-not-null` teme que un cliente existente reciba un nulo que no
-- esperaba: aquí solo puede ocurrir en un perfil anonimizado, la restricción `ck_cp_anonymized` lo
-- impide en uno vivo y quien lee ya trata el anonimizado como inexistente. `constraint-missing-not-valid`
-- es segura: estas tablas las crearon
-- migraciones de hace pocos sprints y aún no existen en ningún entorno desplegado, así que no hay
-- filas que escanear ni escrituras que bloquear. Es la misma razón de V20261122100000.
alter table professional_profiles
    add column anonymized_at timestamptz,
    -- squawk-ignore constraint-missing-not-valid
    add constraint ck_pp_anonymized
        check (anonymized_at is null or (headline is null and bio is null and profile_photo_url is null));

alter table candidate_profiles
    add column anonymized_at timestamptz,
    -- squawk-ignore ban-drop-not-null
    alter column contact_email drop not null,
    -- squawk-ignore constraint-missing-not-valid
    add constraint ck_cp_anonymized
        check (
            (anonymized_at is null and contact_email is not null)
            or (anonymized_at is not null and contact_email is null and contact_phone is null
                and not discoverable and availability_status = 'UNSET'
                and salary_expectation_min is null and salary_expectation_max is null
                and salary_currency is null and salary_period is null)
        );
