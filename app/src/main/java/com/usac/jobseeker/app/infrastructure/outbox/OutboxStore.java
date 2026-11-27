package com.usac.jobseeker.app.infrastructure.outbox;

import com.usac.jobseeker.common.application.outbox.DomainEventPublisherPort;
import com.usac.jobseeker.common.application.outbox.OutboxEvent;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Lado SQL de la bandeja (ADR-0016): publicar en la misma transacción del llamador y, para el
 * sondeador, reclamar filas con `FOR UPDATE SKIP LOCKED` y registrar su resultado.
 */
public class OutboxStore implements DomainEventPublisherPort {

  /** Una fila reclamada, con los intentos que ya lleva. */
  public record Claimed(OutboxEvent event, int attempts) {}

  private static final String ATTEMPT = "attempt";

  private final JdbcClient jdbc;

  public OutboxStore(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public void publish(OutboxEvent event) {
    jdbc.sql(
            "insert into outbox_events (id, aggregate_type, aggregate_id, aggregate_version,"
                + " event_type, event_version, payload, trace_id)"
                + " values (:id, :aggregateType, :aggregateId, :aggregateVersion, :eventType,"
                + " :eventVersion, cast(:payload as jsonb), :traceId)")
        .param("id", event.id())
        .param("aggregateType", event.aggregateType())
        .param("aggregateId", event.aggregateId())
        .param("aggregateVersion", event.aggregateVersion())
        .param("eventType", event.eventType())
        .param("eventVersion", event.eventVersion())
        .param("payload", event.payload())
        .param("traceId", event.traceId())
        .update();
  }

  /**
   * Clave del bloqueo de aviso que serializa los reclamos entre instancias: dos reclamos a la vez
   * no verían los arrendamientos del otro y podrían tomar dos eventos del mismo agregado.
   */
  private static final long CLAIM_LOCK_KEY = 7_262_017_001L;

  /**
   * Reclama filas pendientes ya disponibles y las **arrienda**: marca `locked_until`, cuenta el
   * intento y devuelve las filas. Debe ejecutarse en su propia transacción, corta; quien despacha
   * lo hace después, sin transacción abierta (TD-001).
   *
   * <p>Garantía de orden por agregado (ADR-0016, decisión 4, TD-018): de cada agregado se toma como
   * mucho **un** evento, el de menor versión entre los disponibles, y ninguno si ya hay otro de ese
   * agregado en vuelo. Así nunca corren a la vez dos manejadores del mismo agregado, que podrían
   * leer el estado antes de un cambio y escribir después que el manejador de ese cambio. Un evento
   * que espera su retroceso no bloquea a los siguientes: solo el que está en vuelo.
   *
   * <p>El bloqueo de aviso hace que el reclamo de una instancia vea ya confirmados los
   * arrendamientos de las demás.
   */
  public List<Claimed> claim(int limit, Duration lease, int maxAttempts) {
    jdbc.sql("select pg_advisory_xact_lock(:key)").param("key", CLAIM_LOCK_KEY).query().singleRow();
    failExhaustedLeases(maxAttempts);
    return jdbc.sql(
            "with eligible as ("
                + " select distinct on (o.aggregate_type, o.aggregate_id) o.id, o.available_at"
                + " from outbox_events o"
                + " where o.status = 'PENDING' and o.available_at <= clock_timestamp()"
                + " and (o.locked_until is null or o.locked_until <= clock_timestamp())"
                + " and not exists (select 1 from outbox_events f"
                + " where f.aggregate_type = o.aggregate_type and f.aggregate_id = o.aggregate_id"
                + " and f.status = 'PENDING' and f.locked_until > clock_timestamp())"
                + " order by o.aggregate_type, o.aggregate_id, o.aggregate_version, o.available_at),"
                + " picked as (select id from eligible order by available_at limit :limit)"
                + " update outbox_events e set locked_until = clock_timestamp() + make_interval(secs => :leaseSeconds),"
                + " attempts = e.attempts + 1 from picked"
                + " where e.id = picked.id"
                + " returning e.id, e.aggregate_type, e.aggregate_id, e.aggregate_version,"
                + " e.event_type, e.event_version, e.payload::text as payload, e.trace_id,"
                + " e.attempts, e.available_at")
        .param("limit", limit)
        // pgjdbc no infiere el tipo de un `Duration`: se pasa en segundos.
        .param("leaseSeconds", lease.toMillis() / 1000.0)
        .query(
            (rows, n) ->
                new Claimed(
                    new OutboxEvent(
                        rows.getObject("id", UUID.class),
                        rows.getString("aggregate_type"),
                        rows.getObject("aggregate_id", UUID.class),
                        rows.getLong("aggregate_version"),
                        rows.getString("event_type"),
                        rows.getInt("event_version"),
                        rows.getString("payload"),
                        rows.getString("trace_id")),
                    rows.getInt("attempts")))
        .list();
  }

  // Un evento que mata el proceso nunca llega al camino de fallo del sondeador, así que sin esto se
  // reclamaría sin fin cada vez que venza su arrendamiento. Si vence con los intentos agotados, se
  // da por fallido para revisión, igual que uno que falla con excepción (ADR-0016, decisión 5).
  private void failExhaustedLeases(int maxAttempts) {
    jdbc.sql(
            "update outbox_events set status = 'FAILED', locked_until = null"
                + " where status = 'PENDING' and attempts >= :maxAttempts"
                + " and locked_until <= clock_timestamp()")
        .param("maxAttempts", maxAttempts)
        .update();
  }

  // Las cuatro marcas llevan el intento como testigo de propiedad (*fencing token*): solo escribe
  // quien tiene el arrendamiento vigente. Si venció y otra pasada reclamó la fila, `attempts` ya
  // subió y la marca del dueño anterior no toca nada; sin esto, su `markRetry` borraría el
  // arrendamiento del nuevo dueño y un tercero podría despachar el mismo agregado a la vez.
  private static final String OWNED =
      " where id = :id and attempts = :attempt and status = 'PENDING'";

  /**
   * El intento ya se contó al reclamar: así un evento que mata el proceso no se reintenta sin fin.
   *
   * @return falso si el arrendamiento ya no era de quien marca
   */
  public boolean markProcessed(UUID id, int attempt) {
    return jdbc.sql(
                "update outbox_events set status = 'PROCESSED', processed_at = now(),"
                    + " locked_until = null"
                    + OWNED)
            .param("id", id)
            .param(ATTEMPT, attempt)
            .update()
        == 1;
  }

  /** Programa el reintento: la fila vuelve a estar disponible tras el retroceso. */
  public boolean markRetry(UUID id, int attempt, Duration delay) {
    return jdbc.sql(
                "update outbox_events set available_at = clock_timestamp()"
                    + " + make_interval(secs => :delaySeconds), locked_until = null"
                    + OWNED)
            .param("id", id)
            .param(ATTEMPT, attempt)
            .param("delaySeconds", delay.toMillis() / 1000.0)
            .update()
        == 1;
  }

  /** Agotados los intentos: se deja para revisión, no se reintenta más. */
  public boolean markFailed(UUID id, int attempt) {
    return jdbc.sql("update outbox_events set status = 'FAILED', locked_until = null" + OWNED)
            .param("id", id)
            .param(ATTEMPT, attempt)
            .update()
        == 1;
  }

  /**
   * Devuelve una fila reclamada que no se llegó a despachar: deja de estar arrendada y recupera el
   * intento, que no se gastó. Así un lote lento no consume intentos de los eventos que no tocó.
   */
  public boolean release(UUID id, int attempt) {
    return jdbc.sql("update outbox_events set locked_until = null, attempts = attempts - 1" + OWNED)
            .param("id", id)
            .param(ATTEMPT, attempt)
            .update()
        == 1;
  }

  public long countByStatus(String status) {
    return jdbc.sql("select count(*) from outbox_events where status = :status")
        .param("status", status)
        .query(Long.class)
        .single();
  }

  /** Purga las procesadas hace más que la retención (ADR-0016, decisión 8: 7 días). */
  public int purgeProcessedBefore(Instant cutoff) {
    return jdbc.sql(
            "delete from outbox_events where status = 'PROCESSED' and processed_at < :cutoff")
        .param("cutoff", OffsetDateTime.ofInstant(cutoff, ZoneOffset.UTC))
        .update();
  }
}
