package com.usac.jobseeker.app.infrastructure.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * La purga de retención de la bandeja corta según el reloj inyectado (TD-025, ADR-0016 decisión 8):
 * 7 días para la bandeja procesada y 30 para `processed_events`.
 */
class OutboxPurgeTest {

  private static final Instant NOW = Instant.parse("2026-11-27T03:15:00Z");

  @Test
  void eachTableIsPurgedFromTheInjectedClockWithItsOwnRetention() {
    List<Instant> outboxCutoffs = new ArrayList<>();
    List<Instant> processedCutoffs = new ArrayList<>();
    OutboxStore store =
        new OutboxStore(null) {
          @Override
          public int purgeProcessedBefore(Instant cutoff) {
            outboxCutoffs.add(cutoff);
            return 0;
          }
        };
    JdbcProcessedEvents processed =
        new JdbcProcessedEvents(null) {
          @Override
          public int purgeOlderThan(Instant cutoff) {
            processedCutoffs.add(cutoff);
            return 0;
          }
        };
    var scheduler =
        new OutboxConfiguration.OutboxScheduler(
            null, store, processed, Clock.fixed(NOW, ZoneOffset.UTC));

    scheduler.purge();

    assertThat(outboxCutoffs).containsExactly(NOW.minus(Duration.ofDays(7)));
    assertThat(processedCutoffs).containsExactly(NOW.minus(Duration.ofDays(30)));
  }
}
