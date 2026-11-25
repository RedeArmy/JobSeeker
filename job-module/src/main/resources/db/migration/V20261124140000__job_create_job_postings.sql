-- Vacante y sus requisitos estructurados (data-model.md §4.6 y 03-lld.md §4). Un solo agregado:
-- `job_requirements` es hija de `job_postings` y no se lee ni se escribe por separado.
-- Los vectores de embedding NO están aquí: son de `matching` (data-model.md §4.7.1, J2).
-- Las reglas del dominio se repiten como CHECK: la base es la última defensa, no la única.

create table job_postings (
    id uuid primary key,
    -- Dueño (BR-JOB-01). Sale del token, nunca del cuerpo; que tenga la capacidad lo exige el caso de uso.
    recruiter_account_id uuid not null references accounts (id),
    title text not null,
    description text not null,
    modality text,
    country text,
    salary_min numeric(14, 2),
    salary_max numeric(14, 2),
    -- Un código ISO 4217 tiene siempre 3 letras: char(3) es exacto y no rellena (como `request_hash` de app).
    -- squawk-ignore ban-char-field
    salary_currency char(3),
    salary_period text,
    status text not null default 'DRAFT',
    -- Pausa de postulaciones y etapa de reclutamiento: ejes independientes del estado (BR-JOB-10, BR-JOB-04).
    accepting_applications boolean not null default true,
    recruiting_stage text not null default 'HIRING',
    -- Bloqueo optimista y compare-and-set de toda transición (TDD de job §5, J1): la primera versión es 1.
    version bigint not null default 1,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    published_at timestamptz,
    -- Base del archivado automático (closed_at + archivalDelayDays, BR-JOB-06); se limpia al reabrir (BR-JOB-07).
    closed_at timestamptz,
    archived_at timestamptz,
    -- Su presencia es el tombstone: la fila nunca se borra físicamente (BR-JOB-09).
    deleted_at timestamptz,
    constraint ck_jp_title check (btrim(title) <> '' and char_length(title) <= 150),
    constraint ck_jp_description check (btrim(description) <> '' and char_length(description) <= 10000),
    constraint ck_jp_modality check (modality is null or modality in ('REMOTE', 'HYBRID', 'ONSITE')),
    constraint ck_jp_country check (country is null or country ~ '^[A-Z]{2}$'),
    constraint ck_jp_status check (status in ('DRAFT', 'PUBLISHED', 'CLOSED', 'ARCHIVED', 'DELETED')),
    constraint ck_jp_stage check (recruiting_stage in ('HIRING', 'REVIEWING')),
    constraint ck_jp_salary_range check (salary_min is null or salary_max is null or salary_min <= salary_max),
    constraint ck_jp_salary_positive check ((salary_min is null or salary_min >= 0)
        and (salary_max is null or salary_max >= 0)),
    constraint ck_jp_salary_currency check (salary_currency is null or salary_currency ~ '^[A-Z]{3}$'),
    constraint ck_jp_salary_period check (salary_period is null or salary_period in ('MONTH', 'YEAR')),
    -- BR-JOB-21: moneda, periodo y al menos un importe están todos presentes o todos ausentes.
    constraint ck_jp_salary_complete check (
        (salary_min is null and salary_max is null and salary_currency is null and salary_period is null)
        or ((salary_min is not null or salary_max is not null)
            and salary_currency is not null and salary_period is not null)),
    constraint ck_jp_version check (version >= 1),
    -- Coherencia entre el estado y sus marcas de tiempo: una vacante que nunca se publicó no puede estar
    -- publicada, cerrada ni archivada, y una cerrada tiene su momento de cierre (BR-JOB-06).
    constraint ck_jp_published_at check (status not in ('PUBLISHED', 'CLOSED', 'ARCHIVED') or published_at is not null),
    constraint ck_jp_closed_at check (status <> 'CLOSED' or closed_at is not null),
    constraint ck_jp_deleted_at check ((status = 'DELETED') = (deleted_at is not null))
);

-- Listado del reclutador sobre sus propias vacantes (BR-VIS-03).
create index idx_jp_recruiter on job_postings (recruiter_account_id, created_at desc);
-- Filtro de visibilidad del listado general (BR-JOB-11) en cada consulta.
create index idx_jp_status on job_postings (status);
-- Recorrido del archivado automático, solo las cerradas (BR-JOB-06, Ola 3); parcial para no escanear la tabla.
create index idx_jp_closed on job_postings (closed_at) where status = 'CLOSED';

-- Requisitos estructurados (BR-JOB-02): lo que el score compara con el perfil. Cada tipo usa sus propias
-- columnas, con un CHECK por tipo: el conjunto de tipos es cerrado y pequeño, y así la base rechaza un
-- requisito mal formado (03-lld.md §4 proponía `criteria jsonb`; el modelo genérico de ADR-0007 es de las
-- reglas de elegibilidad, que sí son un catálogo abierto a crecer).
create table job_requirements (
    id uuid primary key,
    job_posting_id uuid not null references job_postings (id),
    requirement_type text not null,
    -- Obligatoria o deseable: solo la obligatoria puede bloquear el score por completitud (TDD de matching).
    is_mandatory boolean not null default false,
    position integer not null,
    -- SKILL: nombre en texto hasta la taxonomía (ADR-0019); `skill_id` espera esa tabla y aún no tiene FK.
    skill_name text,
    skill_id uuid,
    min_level smallint,
    -- EDUCATION
    education_level text,
    -- EXPERIENCE_YEARS
    min_years smallint,
    -- LANGUAGE (ISO 639-1)
    language text,
    min_proficiency text,
    constraint ck_jr_type check (requirement_type in ('SKILL', 'EDUCATION', 'EXPERIENCE_YEARS', 'LANGUAGE')),
    constraint ck_jr_position check (position >= 0),
    constraint ck_jr_skill check (requirement_type <> 'SKILL' or (skill_name is not null
        and btrim(skill_name) <> '' and char_length(skill_name) <= 100
        and (min_level is null or min_level between 1 and 4))),
    constraint ck_jr_education check (requirement_type <> 'EDUCATION' or education_level in
        ('SECONDARY', 'TECHNICAL', 'BACHELOR', 'POSTGRAD_SPECIALIZATION', 'MASTERS', 'DOCTORATE')),
    constraint ck_jr_years check (requirement_type <> 'EXPERIENCE_YEARS' or min_years between 1 and 60),
    constraint ck_jr_language check (requirement_type <> 'LANGUAGE' or (language ~ '^[a-z]{2}$'
        and min_proficiency in ('A1', 'A2', 'B1', 'B2', 'C1', 'C2', 'NATIVE'))),
    -- Un tipo no puede traer columnas de otro: evita datos huérfanos que alguien lea por error.
    constraint ck_jr_no_stray check (
        (requirement_type = 'SKILL' or (skill_name is null and skill_id is null and min_level is null))
        and (requirement_type = 'EDUCATION' or education_level is null)
        and (requirement_type = 'EXPERIENCE_YEARS' or min_years is null)
        and (requirement_type = 'LANGUAGE' or (language is null and min_proficiency is null))),
    constraint uq_jr_position unique (job_posting_id, position)
);
