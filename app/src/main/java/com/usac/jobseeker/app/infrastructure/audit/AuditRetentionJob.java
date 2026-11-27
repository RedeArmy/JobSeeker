package com.usac.jobseeker.app.infrastructure.audit;

import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Purga diaria de `audit_log` según su retención (TD-010). Por tandas y sin transacción propia,
 * como la de los perfiles vistos: cada tanda confirma sola y una purga grande nunca es una
 * transacción enorme.
 */
public class AuditRetentionJob {

  private static final Logger LOG = LoggerFactory.getLogger(AuditRetentionJob.class);

  private final JdbcAuditLog audit;
  private final AuditProperties properties;
  private final MeterRegistry meters;
  private final Clock clock;

  public AuditRetentionJob(
      JdbcAuditLog audit, AuditProperties properties, MeterRegistry meters, Clock clock) {
    this.audit = audit;
    this.properties = properties;
    this.meters = meters;
    this.clock = clock;
  }

  /** Una pasada; devuelve cuántas filas borró. */
  public int purge() {
    Instant cutoff = clock.instant().minus(properties.retention());
    int total = 0;
    for (int batch = 0; batch < properties.purgeMaxBatches(); batch++) {
      int purged = audit.purgeBefore(cutoff, properties.purgeBatchSize());
      total += purged;
      if (purged < properties.purgeBatchSize()) {
        break;
      }
    }
    meters.counter("jobseeker_audit_purged_total").increment(total);
    LOG.info("event=audit_purged purged={} Purga de retención de la auditoría", total);
    return total;
  }

  // Un fallo (la base no responde) no debe detener el planificador: se registra y mañana se repite.
  @SuppressWarnings("PMD.AvoidCatchingGenericException")
  @Scheduled(cron = "${jobseeker.audit.purge-cron:0 45 3 * * *}")
  void run() {
    try {
      purge();
    } catch (RuntimeException failure) {
      LOG.error("event=audit_purge_failed Falló la purga de la auditoría", failure);
    }
  }
}
