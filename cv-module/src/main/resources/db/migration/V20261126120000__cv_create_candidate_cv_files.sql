-- Versiones del CV de un candidato (ADR-0021, tdd/cv-module.md §10.7). El archivo vive en el almacén
-- S3; aquí solo su referencia. Una fila por versión y nunca se reescribe: la postulación guarda la
-- versión con que se envió (ADR-0021 §5), así que el reclutador ve lo que recibió.

create table candidate_cv_files (
    id uuid primary key,
    candidate_profile_id uuid not null references candidate_profiles (id),
    -- Clave aleatoria del almacén: no revela la cuenta ni el nombre (amenaza T-N10). Nula una vez
    -- borrado el objeto al anonimizar.
    object_key text,
    -- Nombre saneado para mostrar y descargar. Puede ser un dato personal («CV Ana López.pdf»), así
    -- que se borra al anonimizar (BR-DATA-05).
    display_name text,
    -- El tipo detectado por el contenido, no el que dijo el cliente.
    file_type text not null,
    size_bytes integer not null,
    -- SHA-256 en hexadecimal, siempre de 64 caracteres: char(64) es exacto, sin relleno (como
    -- `request_hash` de app y `embedding_content_hash` de matching).
    -- squawk-ignore ban-char-field
    sha256 char(64) not null,
    is_current boolean not null,
    uploaded_at timestamptz not null,
    deleted_at timestamptz,
    constraint uq_ccf_object_key unique (object_key),
    constraint ck_ccf_object_key check (object_key is null or object_key ~ '^cv/[0-9a-f-]{36}$'),
    -- Longitud acotada con CHECK y no con varchar(n): ampliarla después no bloquea la tabla.
    constraint ck_ccf_display_name check (display_name is null or char_length(display_name) <= 100),
    constraint ck_ccf_file_type check (file_type in ('PDF', 'DOCX')),
    constraint ck_ccf_size check (size_bytes > 0),
    constraint ck_ccf_sha256 check (sha256 ~ '^[0-9a-f]{64}$'),
    -- Una versión borrada no conserva ni el archivo ni el nombre, y no puede ser la vigente.
    constraint ck_ccf_deleted check (
        deleted_at is null or (object_key is null and display_name is null and not is_current)),
    constraint ck_ccf_live check (deleted_at is not null or (object_key is not null and display_name is not null))
);

-- Una sola versión vigente por perfil; lo garantiza la base, no solo el caso de uso.
create unique index uq_ccf_current on candidate_cv_files (candidate_profile_id) where is_current;
-- El historial de un perfil, de la más reciente a la más antigua.
create index idx_ccf_profile on candidate_cv_files (candidate_profile_id, uploaded_at desc);
