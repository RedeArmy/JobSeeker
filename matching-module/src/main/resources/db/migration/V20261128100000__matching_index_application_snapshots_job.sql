-- El recálculo de una vacante busca los perfiles que postularon a ella (para crear el puntaje que
-- faltaba si al postular no había vector), y el índice que ya existe empieza por el perfil, así que
-- no lo sirve: sin este, cada recálculo de una vacante recorrería la tabla entera.
--
-- Concurrente porque la tabla ya existe: no bloquea las postulaciones mientras se construye. Flyway
-- ejecuta fuera de transacción una migración que solo tiene sentencias que no la admiten, como esta;
-- squawk supone siempre una transacción, de ahí la excepción.
-- squawk-ignore ban-concurrent-index-creation-in-transaction
create index concurrently idx_aps_job on application_profile_snapshots (job_posting_id);
