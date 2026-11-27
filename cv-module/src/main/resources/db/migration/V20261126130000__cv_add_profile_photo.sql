-- Foto de perfil en el almacén S3 (ADR-0021, tdd/cv-module.md §10.8). Reemplaza `profile_photo_url`,
-- que nunca se rellenó: el TDD de cv (D6) prohibió aceptar URLs externas, que filtrarían a un tercero
-- la visita de quien mira el perfil. Ahora la foto es un objeto propio, re-codificado sin metadatos.
--
-- Excepciones de squawk. `ban-drop-column` teme romper a quien lee la columna: nada la lee ni la
-- escribe salvo la anonimización, que esta misma versión pasa a la clave, y la columna siempre ha sido
-- nula. `constraint-missing-not-valid` es segura por lo mismo que en V20261124110000: la tabla aún no
-- existe en ningún entorno desplegado, así que no hay filas que escanear ni escrituras que bloquear.

alter table professional_profiles
    -- La misma sentencia vuelve a crear la garantía con la columna nueva, dentro de la transacción de
    -- Flyway: no hay un instante sin ella.
    -- squawk-ignore ban-drop-constraint
    drop constraint ck_pp_anonymized,
    -- squawk-ignore ban-drop-column
    drop column profile_photo_url,
    -- Clave aleatoria del almacén (`photo/{uuid}`): no revela la cuenta (amenaza T-N10).
    add column profile_photo_key text,
    -- El tipo de la imagen ya re-codificada, con el que se sirve.
    add column profile_photo_type text,
    -- squawk-ignore constraint-missing-not-valid
    add constraint ck_pp_photo_key
        check (profile_photo_key is null or profile_photo_key ~ '^photo/[0-9a-f-]{36}$'),
    -- squawk-ignore constraint-missing-not-valid
    add constraint ck_pp_photo_type
        check (
            (profile_photo_key is null and profile_photo_type is null)
            or (profile_photo_key is not null and profile_photo_type in ('PNG', 'JPEG'))),
    -- Un perfil anonimizado no conserva titular, biografía ni foto (BR-DATA-05).
    -- squawk-ignore constraint-missing-not-valid
    add constraint ck_pp_anonymized
        check (anonymized_at is null or (headline is null and bio is null and profile_photo_key is null));
