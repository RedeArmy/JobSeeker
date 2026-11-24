package com.usac.jobseeker.app.infrastructure.outbox;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Sondeador de la bandeja de salida (ADR-0016, decisiones 4 y 5); nada de esto va fijo en código.
 *
 * @param pollInterval cada cuánto se sondea
 * @param batchSize filas por pasada
 * @param maxAttempts intentos antes de dejar la fila en FAILED para revisión
 * @param baseBackoff retroceso del primer reintento; se duplica en cada uno
 * @param maxBackoff tope del retroceso
 * @param lease cuánto se reserva una fila reclamada: debe superar lo que tarda el consumidor más
 *     lento; si vence, otra instancia la reclama de nuevo (al menos una vez, ADR-0016)
 */
@ConfigurationProperties("jobseeker.outbox")
public record OutboxProperties(
    @DefaultValue("1s") Duration pollInterval,
    @DefaultValue("50") int batchSize,
    @DefaultValue("10") int maxAttempts,
    @DefaultValue("2s") Duration baseBackoff,
    @DefaultValue("5m") Duration maxBackoff,
    @DefaultValue("5m") Duration lease) {

  @ConstructorBinding
  public OutboxProperties {
    if (pollInterval.compareTo(Duration.ZERO) <= 0) {
      throw new IllegalArgumentException("jobseeker.outbox.poll-interval debe ser positivo");
    }
    if (batchSize < 1 || maxAttempts < 1) {
      throw new IllegalArgumentException(
          "jobseeker.outbox.batch-size y max-attempts deben ser positivos");
    }
    if (lease.compareTo(Duration.ZERO) <= 0) {
      throw new IllegalArgumentException("jobseeker.outbox.lease debe ser positivo");
    }
    if (baseBackoff.compareTo(Duration.ZERO) <= 0 || maxBackoff.compareTo(baseBackoff) < 0) {
      throw new IllegalArgumentException(
          "jobseeker.outbox.base-backoff debe ser positivo y no mayor que max-backoff");
    }
  }

  /** Sin arrendamiento explícito: el predeterminado de cinco minutos. */
  public OutboxProperties(
      Duration pollInterval,
      int batchSize,
      int maxAttempts,
      Duration baseBackoff,
      Duration maxBackoff) {
    this(pollInterval, batchSize, maxAttempts, baseBackoff, maxBackoff, Duration.ofMinutes(5));
  }

  /**
   * Retroceso exponencial con jitter completo para el intento `attempt` (1 = el primero): evita que
   * las filas que fallaron juntas reintenten juntas (ADR-0016, decisión 5).
   */
  public Duration backoff(int attempt, double jitter) {
    long millis = baseBackoff.toMillis();
    int doublings = Math.clamp(attempt - 1L, 0, 30);
    long capped = Math.min(maxBackoff.toMillis(), millis << doublings);
    // Entre la mitad y el total: nunca 0, para que un fallo persistente no se reintente en bucle.
    return Duration.ofMillis(Math.max(1, (long) (capped * (0.5 + 0.5 * jitter))));
  }
}
