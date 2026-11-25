-- Verificación de la cuenta reclutadora (REQ-R-10, BR-ACCT-09): sin verificar, una cuenta publica pocas
-- vacantes y no recibe sugerencias; verificada, sube su tope; suspendida, no publica. Es un atributo de la
-- confianza que se le tiene a la cuenta y vive junto a sus capacidades: lo escribe `auth`, y `job` solo lo
-- pregunta por su caso de uso público.
--
-- Todas las cuentas existentes quedan `UNVERIFIED`: es el valor seguro, y verificar es una decisión que
-- alguien toma, nunca un efecto de migrar.
--
-- Excepción de squawk (constraint-missing-not-valid), y es segura por la misma razón que la de
-- `auth_add_identity_state`: `accounts` aún no existe en ningún entorno desplegado (ADR-0026 sin
-- aprobar), así que no hay filas que escanear ni escrituras que bloquear.
alter table accounts
    add column recruiter_verification text not null default 'UNVERIFIED',
    -- squawk-ignore constraint-missing-not-valid
    add constraint ck_accounts_recruiter_verification
        check (recruiter_verification in ('UNVERIFIED', 'VERIFIED', 'SUSPENDED'));
