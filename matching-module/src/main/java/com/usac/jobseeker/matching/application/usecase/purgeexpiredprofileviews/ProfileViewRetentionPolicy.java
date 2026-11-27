package com.usac.jobseeker.matching.application.usecase.purgeexpiredprofileviews;

import java.time.Duration;
import java.util.Objects;

/**
 * El plazo de retención del registro de perfiles vistos (TD-035). Nunca más corto que la ventana de
 * la cuota: borrar una vista que la cuota todavía cuenta le devolvería cupo al reclutador.
 *
 * @param retention cuánto se conserva cada fila; el historial sirve para reconstruir un abuso
 *     (T-N1)
 * @param quotaWindow la ventana móvil de la cuota de los sugeridos
 * @param batchSize filas por tanda de borrado
 * @param maxBatches tandas como mucho por pasada; lo que quede sigue en la siguiente
 */
public record ProfileViewRetentionPolicy(
    Duration retention, Duration quotaWindow, int batchSize, int maxBatches) {

  public ProfileViewRetentionPolicy {
    Objects.requireNonNull(retention, "retention");
    Objects.requireNonNull(quotaWindow, "quotaWindow");
    if (retention.compareTo(quotaWindow) <= 0) {
      throw new IllegalArgumentException(
          "la retención de los perfiles vistos debe superar la ventana de la cuota");
    }
    if (batchSize <= 0 || maxBatches <= 0) {
      throw new IllegalArgumentException("las tandas de la purga deben ser positivas");
    }
  }
}
