package com.usac.jobseeker.app.infrastructure.idempotency;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Retención de `idempotency_keys` (TD-010). 24 horas, lo habitual para estas claves: cubre de sobra
 * los reintentos de un cliente, que llegan en segundos o minutos. Nunca menos de una hora, o un
 * reintento tardío repetiría la postulación en vez de recibir la misma.
 *
 * @param retention cuánto se conserva cada clave
 * @param purgeBatchSize filas por tanda de borrado
 * @param purgeMaxBatches tandas como mucho por pasada; lo que quede sigue en la siguiente
 */
@ConfigurationProperties("jobseeker.idempotency")
public record IdempotencyProperties(
    @DefaultValue("24h") Duration retention,
    @DefaultValue("5000") int purgeBatchSize,
    @DefaultValue("200") int purgeMaxBatches) {

  private static final Duration MIN_RETENTION = Duration.ofHours(1);

  public IdempotencyProperties {
    if (retention.compareTo(MIN_RETENTION) < 0) {
      throw new IllegalArgumentException(
          "jobseeker.idempotency.retention debe ser de al menos una hora (TD-010)");
    }
    if (purgeBatchSize < 1 || purgeMaxBatches < 1) {
      throw new IllegalArgumentException(
          "jobseeker.idempotency.purge-batch-size y purge-max-batches deben ser positivos");
    }
  }
}
