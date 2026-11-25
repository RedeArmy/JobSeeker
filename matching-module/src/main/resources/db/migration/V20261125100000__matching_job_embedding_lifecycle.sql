-- Ciclo de vida del embedding de las vacantes (TD-028). Dos cambios sobre tablas que ya existen:
--
-- 1. `job_embeddings.recruiting_stage`: COPIA de la etapa de reclutamiento de la vacante. Una vacante en
--    `REVIEWING` deja de generar sugeridos de candidatos (BR-MATCH-08) y esa decisión se toma en `matching`
--    sin leer `job`. Como `published`, es una copia que mantienen los eventos de `job`; `HIRING` es el
--    valor de toda vacante publicada (BR-JOB-04), así que es el defecto seguro.
-- 2. La cola de cálculo admite vacantes: `matching_work_queue` solo aceptaba `PROFILE`.
--
-- Excepciones de squawk. `constraint-missing-not-valid` es segura por la misma razón que en las migraciones
-- anteriores de `auth` y `cv`: estas tablas aún no existen en ningún entorno desplegado (ADR-0026 sin
-- aprobar), así que no hay filas que escanear ni escrituras que bloquear.
alter table job_embeddings
    add column recruiting_stage text not null default 'HIRING',
    -- squawk-ignore constraint-missing-not-valid
    add constraint ck_je_recruiting_stage check (recruiting_stage in ('HIRING', 'REVIEWING'));

alter table matching_work_queue
    -- squawk-ignore ban-drop-constraint
    drop constraint ck_mwq_entity_type,
    -- squawk-ignore constraint-missing-not-valid
    add constraint ck_mwq_entity_type check (entity_type in ('PROFILE', 'JOB'));
