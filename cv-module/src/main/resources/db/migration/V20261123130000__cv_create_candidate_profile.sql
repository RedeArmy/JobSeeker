-- Perfil de candidato (data-model.md §4.4 y §4.12): lo propio de la capacidad de candidato sobre el
-- perfil profesional compartido (ADR-0008, BR-ACCT-03). Un perfil por cuenta; que exista el perfil
-- profesional de la misma cuenta lo exige la aplicación, no una clave foránea, porque ambos nacen en
-- momentos distintos y la regla de negocio es del caso de uso.
-- Los vectores de embedding NO están aquí: son de `matching` (data-model.md §4.7.1). El texto del CV
-- subido (`raw_cv_text`) llega con el almacenamiento de archivos (ADR-0021).
-- Las reglas del dominio se repiten como CHECK: la base es la última defensa, no la única.

create table candidate_profiles (
    id uuid primary key,
    account_id uuid not null unique references accounts (id),
    -- Contacto para postular (BR-CAND-01); puede diferir del correo de acceso de la cuenta.
    contact_email text not null,
    contact_phone text,
    -- FUENTE de la descubribilidad (BR-DATA-03, opt-out); `matching` guarda una copia (§4.7.1).
    discoverable boolean not null default true,
    availability_status text not null default 'UNSET',
    -- Datos de elegibilidad (BR-ELIG-01): los evalúan las reglas de `job`, nunca el score (BR-ELIG-06).
    country_of_residence text,
    desired_modality text,
    preferred_work_schedule text,
    relocation_willingness boolean not null default false,
    salary_expectation_min numeric(14, 2),
    salary_expectation_max numeric(14, 2),
    -- Un código ISO 4217 tiene siempre 3 letras: char(3) es exacto y no rellena (como `request_hash` de app).
    -- squawk-ignore ban-char-field
    salary_currency char(3),
    salary_period text,
    -- Bloqueo optimista: la edición exige la versión leída y la incrementa (TDD de cv §5, decisión 3).
    version bigint not null default 0,
    updated_at timestamptz not null default now(),
    constraint ck_cp_contact_email check (btrim(contact_email) <> '' and char_length(contact_email) <= 254),
    constraint ck_cp_contact_phone check (contact_phone is null or char_length(contact_phone) <= 30),
    constraint ck_cp_availability check (availability_status in ('OPEN_TO_WORK', 'NOT_LOOKING', 'UNSET')),
    constraint ck_cp_country check (country_of_residence is null or country_of_residence ~ '^[A-Z]{2}$'),
    constraint ck_cp_modality check (desired_modality is null or desired_modality in ('REMOTE', 'HYBRID', 'ONSITE')),
    constraint ck_cp_schedule check (preferred_work_schedule is null
        or preferred_work_schedule in ('FULL_TIME', 'PART_TIME', 'WEEKENDS')),
    constraint ck_cp_salary_range check (salary_expectation_min is null or salary_expectation_max is null
        or salary_expectation_min <= salary_expectation_max),
    constraint ck_cp_salary_positive check ((salary_expectation_min is null or salary_expectation_min >= 0)
        and (salary_expectation_max is null or salary_expectation_max >= 0)),
    constraint ck_cp_salary_currency check (salary_currency is null or salary_currency ~ '^[A-Z]{3}$'),
    constraint ck_cp_salary_period check (salary_period is null or salary_period in ('MONTH', 'YEAR')),
    -- BR-CAND-07: moneda, periodo y al menos un importe están todos presentes o todos ausentes.
    constraint ck_cp_salary_complete check (
        (salary_expectation_min is null and salary_expectation_max is null
            and salary_currency is null and salary_period is null)
        or ((salary_expectation_min is not null or salary_expectation_max is not null)
            and salary_currency is not null and salary_period is not null))
);

-- Habilidades declaradas (data-model.md §4.12). `skill_id` espera la taxonomía del ADR-0019: existe
-- desde ahora para que ese ADR no reescriba la tabla, y no tiene clave foránea hasta que `skills` exista.
create table candidate_skills (
    id uuid primary key,
    candidate_profile_id uuid not null references candidate_profiles (id),
    raw_name text not null,
    skill_id uuid,
    -- Nivel declarado: 1 básico, 2 intermedio, 3 avanzado, 4 experto (el significado vive en la aplicación).
    proficiency smallint,
    source text not null default 'DECLARED',
    constraint ck_cs_name check (btrim(raw_name) <> '' and char_length(raw_name) <= 100),
    constraint ck_cs_proficiency check (proficiency is null or proficiency between 1 and 4),
    constraint ck_cs_source check (source in ('DECLARED', 'PARSED_CONFIRMED'))
);
-- «Java» y « java » no son dos habilidades.
create unique index uq_cs_profile_name on candidate_skills (candidate_profile_id, lower(btrim(raw_name)));

create table candidate_languages (
    id uuid primary key,
    candidate_profile_id uuid not null references candidate_profiles (id),
    language text not null,
    proficiency text not null,
    constraint ck_cl_language check (language ~ '^[a-z]{2}$'),
    constraint ck_cl_proficiency check (proficiency in ('A1', 'A2', 'B1', 'B2', 'C1', 'C2', 'NATIVE')),
    constraint uq_cl_profile_language unique (candidate_profile_id, language)
);

create table candidate_work_authorizations (
    id uuid primary key,
    candidate_profile_id uuid not null references candidate_profiles (id),
    country text not null,
    constraint ck_cwa_country check (country ~ '^[A-Z]{2}$'),
    constraint uq_cwa_profile_country unique (candidate_profile_id, country)
);
