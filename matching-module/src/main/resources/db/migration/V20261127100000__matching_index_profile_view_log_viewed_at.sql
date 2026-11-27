-- TD-035: la purga de retención borra por antigüedad (`viewed_at < plazo`), y el índice que ya existe
-- empieza por quien mira, así que no la sirve: sin este, cada pasada recorrería la tabla entera.
--
-- Concurrente porque la tabla ya existe: no bloquea las escrituras de los sugeridos mientras se
-- construye. Flyway ejecuta fuera de transacción una migración que solo tiene sentencias que no
-- admiten transacción, como esta; squawk supone siempre una transacción, de ahí la excepción.
-- squawk-ignore ban-concurrent-index-creation-in-transaction
create index concurrently idx_pvl_viewed_at on profile_view_log (viewed_at);
