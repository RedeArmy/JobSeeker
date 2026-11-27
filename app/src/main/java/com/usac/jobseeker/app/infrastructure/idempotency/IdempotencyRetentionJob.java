package com.usac.jobseeker.app.infrastructure.idempotency;

import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Purga de `idempotency_keys` según su retención (TD-010). Por tandas y sin transacción propia,
 * como la de la auditoría: cada tanda confirma sola. Corre cada hora porque la retención es de
 * horas, no de meses: así la tabla nunca guarda más de un día y poco.
 */
public class IdempotencyRetentionJob {

  private static final Logger LOG = LoggerFactory.getLogger(IdempotencyRetentionJob.class);

  private final JdbcIdempotencyKeys keys;
  private final IdempotencyProperties properties;
  private final MeterRegistry meters;
  private final Clock clock;

  public IdempotencyRetentionJob(
      JdbcIdempotencyKeys keys,
      IdempotencyProperties properties,
      MeterRegistry meters,
      Clock clock) {
    this.keys = keys;
    this.properties = properties;
    this.meters = meters;
    this.clock = clock;
  }

  /** Una pasada; devuelve cuántas claves borró. */
  public int purge() {
    Instant cutoff = clock.instant().minus(properties.retention());
    int total = 0;
    for (int batch = 0; batch < properties.purgeMaxBatches(); batch++) {
      int purged = keys.purgeBefore(cutoff, properties.purgeBatchSize());
      total += purged;
      if (purged < properties.purgeBatchSize()) {
        break;
      }
    }
    meters.counter("jobseeker_idempotency_keys_purged_total").increment(total);
    LOG.info("event=idempotency_keys_purged purged={} Purga de claves de idempotencia", total);
    return total;
  }

  // Un fallo (la base no responde) no debe detener el planificador: se registra y se repite.
  @SuppressWarnings("PMD.AvoidCatchingGenericException")
  @Scheduled(cron = "${jobseeker.idempotency.purge-cron:0 15 * * * *}")
  void run() {
    try {
      purge();
    } catch (RuntimeException failure) {
      LOG.error("event=idempotency_purge_failed Falló la purga de claves de idempotencia", failure);
    }
  }
}
