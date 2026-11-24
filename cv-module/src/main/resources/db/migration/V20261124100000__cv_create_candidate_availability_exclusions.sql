-- Lista de exclusión de disponibilidad (data-model.md §4.10, BR-CAND-05): cuentas, de cualquier rol,
-- a las que el candidato oculta su estado OPEN_TO_WORK. Es una relación entre personas, no un
-- control de acceso (BR-CAND-06): su único efecto es el estado de disponibilidad que otro ve.
-- Que el candidato no se excluya a sí mismo es una regla del caso de uso: una restricción CHECK no
-- puede comparar columnas de dos tablas.

create table candidate_availability_exclusions (
    id uuid primary key,
    candidate_profile_id uuid not null references candidate_profiles (id),
    -- Sin restricción de rol: la exclusión es por identidad de cuenta (BR-CAND-05).
    excluded_account_id uuid not null references accounts (id),
    created_at timestamptz not null default now(),
    constraint uq_cae_candidate_account unique (candidate_profile_id, excluded_account_id)
);

-- La unicidad ya indexa «las exclusiones de este candidato»; este índice sirve la otra dirección,
-- necesaria al anonimizar o consultar quién excluyó a una cuenta.
create index idx_cae_excluded_account on candidate_availability_exclusions (excluded_account_id);
