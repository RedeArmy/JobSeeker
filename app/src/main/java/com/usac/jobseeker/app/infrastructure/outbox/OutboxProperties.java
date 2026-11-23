package com.usac.jobseeker.app.infrastructure.outbox;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Sondeador de la bandeja de salida (ADR-0016, decisiones 4 y 5); nada de esto va fijo en código.
 *
 * @param pollInterval cada cuánto se sondea
 * @param batchSize filas por pasada
 * @param maxAttempts intentos antes de dejar la fila en FAILED para revisión
 * @param baseBackoff retroceso del primer reintento; se duplica en cada uno
 * @param maxBackoff tope del retroceso
 */
@ConfigurationProperties("jobseeker.outbox")
public record OutboxProperties(
    @DefaultValue("1s") Duration pollInterval,
    @DefaultValue("50") int batchSize,
    @DefaultValue("10") int maxAttempts,
    @DefaultValue("2s") Duration baseBackoff,
    @DefaultValue("5m") Duration maxBackoff) {

  public OutboxProperties {
    if (pollInterval.compareTo(Duration.ZERO) <= 0) {
      throw new IllegalArgumentException("jobseeker.outbox.poll-interval debe ser positivo");
    }
    if (batchSize < 1 || maxAttempts < 1) {
      throw new IllegalArgumentException(
          "jobseeker.outbox.batch-size y max-attempts deben ser positivos");
    }
    if (baseBackoff.compareTo(Duration.ZERO) <= 0 || maxBackoff.compareTo(baseBackoff) < 0) {
      throw new IllegalArgumentException(
          "jobseeker.outbox.base-backoff debe ser positivo y no mayor que max-backoff");
    }
  }

  /**
   * Retroceso exponencial con jitter completo para el intento `attempt` (1 = el primero): evita que
   * las filas que fallaron juntas reintenten juntas (ADR-0016, decisión 5).
   */
  public Duration backoff(int attempt, double jitter) {
    long millis = baseBackoff.toMillis();
    int doublings = Math.min(Math.max(attempt - 1, 0), 30);
    long capped = Math.min(maxBackoff.toMillis(), millis << doublings);
    // Entre la mitad y el total: nunca 0, para que un fallo persistente no se reintente en bucle.
    return Duration.ofMillis(Math.max(1, (long) (capped * (0.5 + 0.5 * jitter))));
  }
}
