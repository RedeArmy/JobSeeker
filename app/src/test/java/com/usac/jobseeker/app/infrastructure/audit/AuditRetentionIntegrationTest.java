package com.usac.jobseeker.app.infrastructure.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.usac.jobseeker.app.InfrastructureTestConfiguration;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * La retención de `audit_log` contra Postgres real (TD-010): se borra solo lo anterior al plazo,
 * por tandas. Con las anotaciones del grupo principal de pruebas reutiliza su contexto (TD-017).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(InfrastructureTestConfiguration.class)
class AuditRetentionIntegrationTest {

  private static final Instant NOW = Instant.parse("2026-11-27T03:45:00Z");

  @Autowired private JdbcAuditLog audit;
  @Autowired private JdbcTemplate jdbc;

  @BeforeEach
  void clean() {
    jdbc.update("delete from audit_log");
  }

  private void actionAt(Instant at) {
    jdbc.update(
        "insert into audit_log (id, at, actor_account_id, action, target_type)"
            + " values (?, ?, ?, 'recruiter.reviewed', 'account')",
        UUID.randomUUID(),
        OffsetDateTime.ofInstant(at, ZoneOffset.UTC),
        UUID.randomUUID());
  }

  private int rows() {
    return jdbc.queryForObject("select count(*) from audit_log", Integer.class);
  }

  @Test
  void onlyActionsOlderThanTheRetentionArePurgedInBatches() {
    for (int i = 0; i < 5; i++) {
      actionAt(NOW.minus(Duration.ofDays(800)));
    }
    actionAt(NOW.minus(Duration.ofDays(400)));
    actionAt(NOW.minus(Duration.ofHours(1)));
    var meters = new SimpleMeterRegistry();
    var job =
        new AuditRetentionJob(
            audit,
            new AuditProperties(Duration.ofDays(730), 2, 100),
            meters,
            Clock.fixed(NOW, ZoneOffset.UTC));

    int purged = job.purge();

    assertThat(purged).isEqualTo(5);
    assertThat(rows()).isEqualTo(2);
    assertThat(meters.counter("jobseeker_audit_purged_total").count()).isEqualTo(5.0);
  }

  // Una pasada tiene tope: lo que queda sigue al día siguiente.
  @Test
  void aPassStopsAtItsMaximumNumberOfBatches() {
    for (int i = 0; i < 5; i++) {
      actionAt(NOW.minus(Duration.ofDays(800)));
    }
    var job =
        new AuditRetentionJob(
            audit,
            new AuditProperties(Duration.ofDays(730), 2, 1),
            new SimpleMeterRegistry(),
            Clock.fixed(NOW, ZoneOffset.UTC));

    assertThat(job.purge()).isEqualTo(2);
    assertThat(rows()).isEqualTo(3);
  }

  @Test
  void theRetentionCannotBeShorterThanAYearAndTheBatchesMustBePositive() {
    Duration halfYear = Duration.ofDays(180);
    Duration twoYears = Duration.ofDays(730);

    assertThatThrownBy(() -> new AuditProperties(halfYear, 10, 1))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new AuditProperties(twoYears, 0, 1))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new AuditProperties(twoYears, 1, 0))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
