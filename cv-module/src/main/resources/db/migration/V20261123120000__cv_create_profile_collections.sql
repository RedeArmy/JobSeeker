-- Colecciones del perfil profesional (data-model.md §4.3): experiencia, formación, certificaciones y
-- proyectos. Son hijas del agregado `professional_profiles` (ADR-0008) y las comparten todas las
-- capacidades de la cuenta. Cada fila lleva su propio identificador porque se edita elemento a elemento.
-- La anonimización las elimina físicamente (BR-DATA-05): a diferencia de `Application`, nadie más tiene
-- interés en conservar el detalle laboral de quien pidió ser olvidado.
-- Las longitudes repiten las del dominio: la base es la última defensa, no la única.

create table experiences (
    id uuid primary key,
    professional_profile_id uuid not null references professional_profiles (id),
    company text not null,
    title text not null,
    start_date date not null,
    -- NULL significa «experiencia actual».
    end_date date,
    description text,
    constraint ck_exp_company check (btrim(company) <> '' and char_length(company) <= 200),
    constraint ck_exp_title check (btrim(title) <> '' and char_length(title) <= 200),
    constraint ck_exp_description check (description is null or char_length(description) <= 4000),
    constraint ck_exp_dates check (start_date between date '1900-01-01' and date '2100-12-31'
        and (end_date is null or (end_date >= start_date and end_date <= date '2100-12-31')))
);
create index idx_experiences_profile on experiences (professional_profile_id);

create table education_entries (
    id uuid primary key,
    professional_profile_id uuid not null references professional_profiles (id),
    institution text not null,
    degree text not null,
    -- Nivel normalizado, distinto del título libre: el más alto lo evalúan las reglas de elegibilidad
    -- ACADEMIC_LEVEL (BR-ELIG-01), porque `degree` no es un valor contra el que se pueda comparar.
    level text,
    -- NULL significa «en curso».
    completion_date date,
    constraint ck_edu_institution check (btrim(institution) <> '' and char_length(institution) <= 200),
    constraint ck_edu_degree check (btrim(degree) <> '' and char_length(degree) <= 200),
    constraint ck_edu_level check (level is null or level in
        ('SECONDARY', 'TECHNICAL', 'BACHELOR', 'POSTGRAD_SPECIALIZATION', 'MASTERS', 'DOCTORATE')),
    constraint ck_edu_date check (completion_date is null
        or completion_date between date '1900-01-01' and date '2100-12-31')
);
create index idx_education_profile on education_entries (professional_profile_id);

create table certifications (
    id uuid primary key,
    professional_profile_id uuid not null references professional_profiles (id),
    name text not null,
    issuing_organization text not null,
    issue_date date,
    -- NULL significa «no expira».
    expiration_date date,
    credential_url text,
    constraint ck_cert_name check (btrim(name) <> '' and char_length(name) <= 200),
    constraint ck_cert_issuer check (btrim(issuing_organization) <> '' and char_length(issuing_organization) <= 200),
    constraint ck_cert_url check (credential_url is null
        or (char_length(credential_url) <= 2000 and credential_url ~ '^https?://')),
    constraint ck_cert_dates check (
        (issue_date is null or issue_date between date '1900-01-01' and date '2100-12-31')
        and (expiration_date is null or expiration_date between date '1900-01-01' and date '2100-12-31')
        and (issue_date is null or expiration_date is null or expiration_date >= issue_date))
);
create index idx_certifications_profile on certifications (professional_profile_id);

create table projects (
    id uuid primary key,
    professional_profile_id uuid not null references professional_profiles (id),
    name text not null,
    description text,
    -- Distingue el proyecto laboral del personal, tal como lo pidió el negocio.
    type text not null,
    role text,
    start_date date,
    -- NULL significa «en curso».
    end_date date,
    url text,
    constraint ck_proj_name check (btrim(name) <> '' and char_length(name) <= 200),
    constraint ck_proj_description check (description is null or char_length(description) <= 4000),
    constraint ck_proj_type check (type in ('WORK', 'PERSONAL')),
    constraint ck_proj_role check (role is null or char_length(role) <= 200),
    constraint ck_proj_url check (url is null or (char_length(url) <= 2000 and url ~ '^https?://')),
    constraint ck_proj_dates check (
        (start_date is null or start_date between date '1900-01-01' and date '2100-12-31')
        and (end_date is null or end_date between date '1900-01-01' and date '2100-12-31')
        and (start_date is null or end_date is null or end_date >= start_date))
);
create index idx_projects_profile on projects (professional_profile_id);
