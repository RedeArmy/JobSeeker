# ADR-0016: Entrega de eventos de dominio mediante *outbox* transaccional
## Sistema Inteligente de Análisis de Compatibilidad CV-Vacante con CRM de Postulaciones

| Campo | Valor |
|---|---|
| **Estado** | ✅ Aprobado — 18-nov-2026, por el responsable del proyecto, tras la validación completa descrita al final de este documento |
| **Versión** | 1.1 — ajustado el 18-nov-2026 tras la validación completa |
| **Fecha** | Noviembre 2026 |
| **Documento relacionado** | `domain-model.md` §6 (24 eventos), BR-MATCH-03, BR-DATA-05/06, ADR-0013, ADR-0015, `architecture-review-2026-11/02-hld-system-design.md` §6 |

---

## Contexto

`domain-model.md` §6 define 24 eventos de dominio (`ProfileUpdatedEvent`, `JobPostingClosedEvent`, `AccountAnonymizedEvent`, …) pero no su **semántica de entrega**. Con el publicador de eventos en memoria de Spring, si el proceso cae entre el *commit* de la transacción y la ejecución del consumidor, el evento se pierde para siempre:

- Un `ProfileUpdatedEvent` perdido deja un score obsoleto de forma permanente, violando BR-MATCH-03 ("el score nunca refleja datos obsoletos").
- Un evento perdido en la cascada de borrado de cuenta (BR-DATA-06) deja PII huérfana en Clavaris.
- Los consumidores (`matching`, `ats`, `tracker`) viven en el mismo proceso, pero el trabajo que desencadenan (embeddings con Ollama, correo, llamadas a Clavaris) es lento y falible, y no debe correr dentro de la transacción del caso de uso.

## Decisión

Implementar el patrón de **bandeja de salida transaccional** (*transactional outbox*, en adelante *outbox*), propio y mínimo, en `common` + adaptador de infraestructura:

1. **Escritura atómica.** El caso de uso persiste el cambio de dominio **y** una fila en `outbox_events` en la **misma transacción**. El dominio nunca habla con el *outbox*: los agregados acumulan eventos y el caso de uso los entrega a un puerto `DomainEventPublisherPort` (interfaz en `application`), implementado por un adaptador que inserta en `outbox_events`.
2. **Despacho asíncrono.** Un sondeador periódico (*poller*, `@Scheduled`, intervalo corto configurable, por defecto 1 s) reclama filas pendientes con `SELECT … FOR UPDATE SKIP LOCKED LIMIT n`, las entrega a los consumidores registrados y las marca `processed_at`. `SKIP LOCKED` hace seguro correr más de una instancia sin duplicar trabajo.
3. **Garantía: al menos una vez.** Los consumidores deben ser **idempotentes**: cada consumidor registra `(consumer, event_id)` en `processed_events` (o usa `INSERT … ON CONFLICT DO NOTHING`) antes de aplicar efectos.
4. **Orden.** No se garantiza orden global; se garantiza por agregado mediante `aggregate_version`. Los consumidores son de **nivel disparado** (releen el estado actual al procesar), de modo que un evento viejo reprocesado no revierte nada.
5. **Reintentos.** Espera exponencial creciente (*backoff*) con variación aleatoria (*jitter*) (`available_at` futuro, `attempts + 1`). Superado `maxAttempts` (configurable) la fila pasa a `FAILED`, emite la métrica `outbox_failed_total` y dispara alerta; nunca se descarta en silencio.
6. **Contenido del evento.** Solo identificadores, versión de esquema (`event_version`) y metadatos mínimos — **nunca PII** (BR-DATA-01). El consumidor lee el dato desde su puerto cuando lo necesita.
7. **Efectos fuera del sistema** (correo, llamadas a Clavaris, Ollama) se ejecutan **solo** desde consumidores del *outbox*, nunca dentro de la transacción del caso de uso.
8. **Retención.** Filas de `outbox_events` procesadas se purgan tras 7 días (configurable); `FAILED` se conservan hasta resolverse. `processed_events` se conserva **al menos 30 días**, más que cualquier ventana de reintento o reenvío de un emisor externo (la deduplicación de los webhooks de Clavaris depende de ello); se purga por lotes.
9. **Observabilidad.** Métricas: profundidad de la cola, edad de la fila pendiente más antigua (retraso o *lag*), intentos, fallos, duración por consumidor; `traceId` se propaga en el evento para correlación de logs (NFR-OBS-01).
10. **Nunca se escribe en base de datos desde `afterCommit`.** Un `TransactionSynchronization.afterCommit` se ejecuta dentro de una transacción ya completada: una escritura con `@Transactional` (propagación por defecto) se une a ella y falla o se pierde. Lo comprobó Clavaris: su borrado de organización con Redis caído dejó sin ejecutar el cierre de sesiones y la purga de claves (TD-SEC-102). Todo trabajo posterior al *commit* con escritura va por el *outbox*; ARCH-10 (ADR-0015) lo hace cumplir.
11. **Eventos entrantes de terceros (webhooks de Clavaris, ADR-0020).** Se reducen a identificadores **antes** de persistirse: el cuerpo recibido trae correo, nombre y teléfono, y el *outbox* nunca guarda PII (decisión 6). El receptor verifica la firma, y en **una sola transacción** inserta `(consumer, event_id)` en `processed_events` con `ON CONFLICT DO NOTHING`, aplica el efecto sobre la base local (verificado bajo concurrencia, `06-…` addendum 4) y, si hace falta un efecto fuera de la base (p. ej. cerrar sesiones en Redis), escribe un evento en `outbox_events`. Así Redis caído no pierde ni retrasa la aceptación del webhook.
12. **Registro de consumidores.** Los consumidores implementan una interfaz `OutboxEventConsumer` definida en `common`; cada módulo aporta los suyos en `infrastructure` y el sondeador, alojado en `app`, los descubre. Un módulo no importa los consumidores de otro (ARCH-04).

Esquema (ilustrativo; la migración real vive en Flyway):

```sql
CREATE TABLE outbox_events (
  id uuid PRIMARY KEY, aggregate_type text NOT NULL, aggregate_id uuid NOT NULL,
  aggregate_version bigint NOT NULL, event_type text NOT NULL, event_version int NOT NULL DEFAULT 1,
  payload jsonb NOT NULL, trace_id text,
  created_at timestamptz NOT NULL DEFAULT now(), available_at timestamptz NOT NULL DEFAULT now(),
  attempts int NOT NULL DEFAULT 0, status text NOT NULL DEFAULT 'PENDING'
    CHECK (status IN ('PENDING','PROCESSED','FAILED')), processed_at timestamptz);
CREATE INDEX idx_outbox_pending ON outbox_events (available_at) WHERE status = 'PENDING';
CREATE TABLE processed_events (
  consumer text NOT NULL, event_id uuid NOT NULL, processed_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (consumer, event_id));
```

## Alternativas consideradas

| Alternativa | Por qué se rechazó |
|---|---|
| `ApplicationEventPublisher` en memoria (estado actual) | Pierde eventos ante caídas; ejecuta trabajo lento dentro o pegado a la transacción |
| Registro de publicación de eventos de Spring Modulith | Hace el trabajo equivalente pero acopla `application/` a anotaciones de framework y arrastra una dependencia con su propio ciclo de versiones (ver ADR-0015) |
| Broker externo (Kafka, RabbitMQ, SQS) | Infraestructura adicional para un único proceso; viola el espíritu de ADR-0001 y la restricción de operación por una sola persona |
| `LISTEN/NOTIFY` de Postgres | No es durable por sí solo y se comporta mal con agrupadores de conexiones (*poolers*) en modo transacción (Supabase); se puede añadir luego como optimización de latencia sobre el *polling*, no como mecanismo de entrega |
| CDC (Debezium) | Complejidad operativa desproporcionada para el alcance |

## Consecuencias

**Positivas**
- Ningún evento se pierde por una caída; BR-MATCH-03 y BR-DATA-06 se vuelven cumplibles.
- Desacopla latencia y fallos de servicios lentos (Ollama, correo, Clavaris) del camino de usuario.
- Sin infraestructura nueva (usa Postgres); preparado para más de una instancia.

**Negativas / trade-offs aceptados**
- **Consistencia eventual:** hay un retraso de ~1 s (+ procesamiento) entre el cambio y su efecto en otros módulos. La UI debe comunicarlo ("actualizando…") — alineado con el SLO de frescura del matching.
- Cada consumidor debe diseñarse como idempotente y probarse (prueba de "entrega duplicada").
- Mantener un componente propio (~150 líneas + pruebas) en vez de una biblioteca.
- **Precedente favorable:** `../Clavaris` usa el mismo patrón (`event_outbox` con `SKIP LOCKED`, entrega con reintentos y reenvío con el mismo `Event-Id`), verificado de extremo a extremo en `06-spike-contrato-clavaris.md`.

## Acciones derivadas

- Migración Flyway con `outbox_events` y `processed_events` en el Sprint 0 (esqueleto funcional o *walking skeleton*).
- Prueba de integración "mata el proceso entre commit y despacho": el evento se entrega al reiniciar.
- Prueba de entrega duplicada por consumidor.
- ArchUnit (ADR-0015): `@Scheduled` solo en `infrastructure`.

## Preguntas abiertas

- Valor de `maxAttempts` y de la ventana de espera (*backoff*) (propuesta inicial: 8 intentos, tope de 30 min).
- ¿Se necesita partición por tipo de evento si el volumen crece (p. ej. aislar la cola de embeddings de la de notificaciones)? Probablemente se resuelve con *workers* separados sobre la misma tabla filtrando por `event_type`.

## Validación previa a la aprobación (18-nov-2026)

**Veredicto: aprobar con los ajustes ya aplicados.**

- **Conflicto resuelto con ADR-0020:** este ADR prohíbe PII en el *outbox* y el ADR-0020 mandaba persistir en él el cuerpo del webhook de Clavaris, que trae correo (en `account.created`/`deleted`), nombre y teléfono (en `profile_updated`). Las decisiones 11 y 12 lo reconcilian: se reduce a identificadores antes de persistir, y la deduplicación y el efecto sobre la base ocurren en una sola transacción, como se verificó bajo concurrencia (dedup con `ON CONFLICT`, orden `(occurredAt, event_id)`, borrado terminal).
- **Lección incorporada de Clavaris** (TD-SEC-102, decisión 10): escribir desde `afterCommit` falla; el diseño de este ADR ya lo evitaba al usar sondeo, ahora queda explícito y con regla ARCH-10.
- **Retención de `processed_events`** estaba sin definir y la deduplicación de eventos externos depende de ella (pendiente señalado en el addendum 4 del dossier).
- **`SKIP LOCKED` y el *pooler* (verificado el 18-nov-2026 con PgBouncer 1.26 en modo transacción como emulación; Supabase real no probado):** `SELECT … FOR UPDATE SKIP LOCKED` dentro de una transacción funciona en modo transacción. El sondeador debe usar **solo** transacciones: un `SET` de sesión contamina a otros clientes del *pooler* (un cliente fijó `strict_order` y los tres siguientes lo vieron) y un bloqueo de sesión (`pg_advisory_lock`) queda retenido en el servidor aunque el cliente se vaya y otro cliente lo «adquiere» (exclusión mutua rota). Se usa `SET LOCAL` y nunca bloqueos de sesión. Pendiente comprobar con la instancia real de Supabase.

## Enmienda del 24-nov-2026: despacho sin transacción y orden por agregado (TD-001, TD-018)

La auditoría del 24-nov-2026 encontró que la implementación no cumplía dos de las promesas de este
ADR, y que una de ellas se habría vuelto un problema real al llegar el primer consumidor que llama a
Ollama. Se corrige el sondeador; las decisiones 1, 3, 5, 6 y 8 a 12 no cambian.

**Decisión 2 (despacho), enmendada.** El reclamo y el despacho dejan de compartir una transacción:

1. **Reclamo corto.** Una transacción de milisegundos marca las filas con `locked_until = ahora +
   arrendamiento` (por defecto 5 min, `jobseeker.outbox.lease`), cuenta el intento y las devuelve.
2. **Despacho sin transacción.** Los consumidores se llaman sin transacción abierta ni bloqueos de
   fila. Antes, el reclamo de hasta 50 filas, la llamada a cada consumidor y el marcado ocurrían en una
   sola transacción: un consumidor que tardara (Ollama) retenía una conexión del pool y los bloqueos
   durante hasta 50 llamadas seguidas.
3. **Resultado.** `PROCESSED`, reintento con retroceso (`available_at` futuro) o `FAILED`, cada uno en
   su propia sentencia.
4. **Si el proceso muere con filas en vuelo**, el arrendamiento vence y otra instancia las reclama de
   nuevo: al menos una vez, que es lo que ya prometía la decisión 3. El intento se cuenta **al
   reclamar**, de modo que un evento que mata el proceso en bucle llega a `maxAttempts` y pasa a
   `FAILED` en vez de reclamarse sin fin.
5. **Si falla el propio marcado** (la base de datos cae justo después de que el consumidor terminó),
   la fila sigue arrendada y se reentrega al vencer el arrendamiento; los consumidores son
   idempotentes. Es redundancia aceptada, no pérdida.

**Decisión 4 (orden), enmendada.** «Se garantiza orden por agregado mediante `aggregate_version`» no
era cierto: con `SKIP LOCKED` dos instancias podían despachar a la vez la v5 y la v6 del mismo
agregado, y el manejador de la v5 podía leer el estado antes de la v6 y escribir después que el de la
v6. Ahora el reclamo toma **como mucho un evento por agregado** (el de menor versión entre los
disponibles) y **ninguno si ya hay otro de ese agregado en vuelo**. Un evento que espera su reintento
no bloquea a los siguientes: solo el que está en vuelo. Los reclamos se serializan con un bloqueo de
aviso de transacción (`pg_advisory_xact_lock`), para que cada uno vea ya confirmados los
arrendamientos de los demás; funciona con el agrupador en modo transacción porque se libera al
confirmar.

Consecuencias y límites:

- Se despacha **un evento por agregado y por pasada**. Una ráfaga de ediciones de un mismo perfil
  necesita tantas pasadas como eventos, así que el planificador repite pasadas mientras haya trabajo
  (hasta 20 por sondeo) y no espera al siguiente.
- El reclamo está serializado por un bloqueo global. Dura milisegundos y es correcto para una
  aplicación de un proceso o unas pocas instancias; si el volumen lo hiciera un cuello de botella, la
  salida es particionar la clave del bloqueo por rango de agregado.
- Los consumidores siguen siendo de **nivel disparado**: releen el estado actual y no copian valores de
  la carga. Eso, más la ausencia de manejadores simultáneos por agregado, es lo que hace correcto el
  orden. El contrato está en la documentación de `OutboxEventConsumer` y se comprueba sobre los beans
  reales (nombres únicos y no vacíos, porque son la clave de `processed_events`).
- Nuevo índice de lectura: `idx_outbox_aggregate` ya tiene un lector, el reclamo (la ficha TD-009 se
  descarta por eso).

Pruebas que fijan el comportamiento (contra Postgres real): un consumidor lento no deja ninguna sesión
con transacción abierta ni la fila bloqueada; una fila en vuelo no la reclama otra instancia; una fila
cuyo proceso murió se reclama al vencer el arrendamiento; un evento que mata el proceso en bucle acaba
en `FAILED`; nunca corren dos manejadores del mismo agregado con cuatro instancias; entre los
disponibles va primero la menor versión; y un reintento en espera no bloquea la versión siguiente.
Las dos garantías principales se verificaron además restaurando el comportamiento anterior y viendo
fallar la prueba.
