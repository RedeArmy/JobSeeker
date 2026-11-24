package com.usac.jobseeker.matching.application.usecase.embedentity;

import java.time.Duration;
import java.util.Objects;

/**
 * Cómo se trabaja la cola de embeddings; todo viene de configuración, nada va fijo (BR-MATCH-05).
 *
 * @param batchSize trabajos por pasada
 * @param lease cuánto se reserva un trabajo reclamado: debe superar lo que tarda el modelo, o se
 *     reclamaría dos veces
 * @param maxAttempts intentos antes de dejarlo en FAILED para revisión
 * @param baseBackoff espera del primer reintento; se duplica en cada uno
 * @param maxBackoff tope de la espera
 */
public record EmbeddingQueuePolicy(
    int batchSize, Duration lease, int maxAttempts, Duration baseBackoff, Duration maxBackoff) {

  public EmbeddingQueuePolicy {
    Objects.requireNonNull(lease, "lease");
    Objects.requireNonNull(baseBackoff, "baseBackoff");
    Objects.requireNonNull(maxBackoff, "maxBackoff");
    if (batchSize < 1 || maxAttempts < 1) {
      throw new IllegalArgumentException("batchSize y maxAttempts deben ser positivos");
    }
    if (lease.isZero()
        || lease.isNegative()
        || baseBackoff.isZero()
        || baseBackoff.isNegative()
        || maxBackoff.compareTo(baseBackoff) < 0) {
      throw new IllegalArgumentException("lease y backoff deben ser positivos, y max >= base");
    }
  }

  /** Espera antes del intento siguiente: exponencial desde la base, con tope. */
  public Duration backoff(int attempt) {
    int doublings = Math.clamp(attempt - 1L, 0, 30);
    long capped = Math.min(maxBackoff.toMillis(), baseBackoff.toMillis() << doublings);
    return Duration.ofMillis(Math.max(1, capped));
  }
}
