-- TD-010: la purga de retención borra por antigüedad (`at < plazo`), y los índices que ya existen
-- empiezan por el actor o por el objetivo, así que no la sirven: sin este, cada pasada recorrería
-- la tabla entera.
--
-- Concurrente porque la tabla ya existe: no bloquea las escrituras mientras se construye. Flyway
-- ejecuta fuera de transacción una migración que solo tiene sentencias que no la admiten, como
-- esta; squawk supone siempre una transacción, de ahí la excepción.
-- squawk-ignore ban-concurrent-index-creation-in-transaction
create index concurrently idx_audit_at on audit_log (at);
