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
   * Reclama filas pendientes ya disponibles. `SKIP LOCKED` permite que varias instancias sondeen a
   * la vez sin pisarse ni esperarse. Se ordena por agregado y versión para respetar el orden dentro
   * de un agregado cuando hay una sola instancia.
   */
  public List<Claimed> claim(int limit) {
    return jdbc.sql(
            "select id, aggregate_type, aggregate_id, aggregate_version, event_type,"
                + " event_version, payload::text as payload, trace_id, attempts"
                + " from outbox_events where status = 'PENDING' and available_at <= now()"
                + " order by available_at, aggregate_id, aggregate_version"
                + " limit :limit for update skip locked")
        .param("limit", limit)
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

  public void markProcessed(UUID id) {
    jdbc.sql(
            "update outbox_events set status = 'PROCESSED', processed_at = now(),"
                + " attempts = attempts + 1 where id = :id")
        .param("id", id)
        .update();
  }

  /** Programa el reintento: la fila vuelve a estar disponible tras el retroceso. */
  public void markRetry(UUID id, Duration delay) {
    jdbc.sql(
            "update outbox_events set attempts = attempts + 1,"
                + " available_at = :availableAt where id = :id")
        .param("id", id)
        .param("availableAt", OffsetDateTime.ofInstant(Instant.now().plus(delay), ZoneOffset.UTC))
        .update();
  }

  /** Agotados los intentos: se deja para revisión, no se reintenta más. */
  public void markFailed(UUID id) {
    jdbc.sql("update outbox_events set status = 'FAILED', attempts = attempts + 1 where id = :id")
        .param("id", id)
        .update();
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
