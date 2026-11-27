package com.usac.jobseeker.app.infrastructure.audit;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Retención de `audit_log` (TD-010). 24 meses, como las fotos del puntaje: lo que se audita hoy son
 * las decisiones de los operadores sobre reclutadores (ADR-0029), y una suspensión debe poder
 * justificarse mientras se pueda reclamar. Nunca menos de un año.
 *
 * @param retention cuánto se conserva cada acción auditada
 * @param purgeBatchSize filas por tanda de borrado
 * @param purgeMaxBatches tandas como mucho por pasada; lo que quede sigue al día siguiente
 */
@ConfigurationProperties("jobseeker.audit")
public record AuditProperties(
    @DefaultValue("730d") Duration retention,
    @DefaultValue("5000") int purgeBatchSize,
    @DefaultValue("200") int purgeMaxBatches) {

  private static final Duration MIN_RETENTION = Duration.ofDays(365);

  public AuditProperties {
    if (retention.compareTo(MIN_RETENTION) < 0) {
      throw new IllegalArgumentException(
          "jobseeker.audit.retention debe ser de al menos un año (TD-010)");
    }
    if (purgeBatchSize < 1 || purgeMaxBatches < 1) {
      throw new IllegalArgumentException(
          "jobseeker.audit.purge-batch-size y purge-max-batches deben ser positivos");
    }
  }
}
