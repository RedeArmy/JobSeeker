package com.usac.jobseeker.app.infrastructure.outbox;

import com.usac.jobseeker.common.application.idempotency.ProcessedEventsPort;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * `processed_events` con restricción única (ADR-0016, decisiones 3 y 11). Debe ejecutarse dentro de
 * la transacción del efecto: si esa transacción se revierte, la marca también, y el evento sigue
 * siendo reintentable.
 */
public class JdbcProcessedEvents implements ProcessedEventsPort {

  private final JdbcClient jdbc;

  public JdbcProcessedEvents(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public boolean markProcessed(String consumer, UUID eventId) {
    return jdbc.sql(
                "insert into processed_events (consumer, event_id) values (:consumer, :eventId)"
                    + " on conflict do nothing")
            .param("consumer", consumer)
            .param("eventId", eventId)
            .update()
        == 1;
  }

  /** Purga lo anterior a la retención (≥ 30 días: más que cualquier ventana de reintento). */
  public int purgeOlderThan(java.time.Instant cutoff) {
    return jdbc.sql("delete from processed_events where processed_at < :cutoff")
        .param("cutoff", OffsetDateTime.ofInstant(cutoff, ZoneOffset.UTC))
        .update();
  }
}
