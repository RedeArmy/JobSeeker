-- Perfil profesional compartido (data-model.md §4.2, ADR-0008): un núcleo por cuenta, reutilizado por
-- cualquiera de sus capacidades (BR-ACCT-02). Las tablas hijas (experiencia, formación, certificaciones y
-- proyectos) llegan con el sub-paso 2 del TDD de cv, junto con los casos de uso que las escriben.
-- Se usa `text` con restricciones de longitud en vez de `varchar(n)`: cambiar un límite es más barato.
create table professional_profiles (
    id uuid primary key,
    -- Un perfil por cuenta; la FK entre contextos es aceptable en el monolito (03-lld.md §4.3).
    account_id uuid not null unique references accounts (id),
    full_name text not null,
    headline text,
    bio text,
    -- Sin uso en la Ola 1: no se aceptan URLs externas hasta que exista el almacén de archivos (ADR-0021).
    profile_photo_url text,
    -- Bloqueo optimista: la edición exige la versión leída y la incrementa (TDD de cv §5, decisión 3).
    version bigint not null default 0,
    updated_at timestamptz not null default now(),
    constraint ck_pp_full_name check (btrim(full_name) <> '' and char_length(full_name) <= 200),
    constraint ck_pp_headline check (headline is null or char_length(headline) <= 200),
    constraint ck_pp_bio check (bio is null or char_length(bio) <= 4000)
);
