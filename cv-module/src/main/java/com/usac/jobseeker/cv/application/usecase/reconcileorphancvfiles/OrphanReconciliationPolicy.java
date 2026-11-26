package com.usac.jobseeker.cv.application.usecase.reconcileorphancvfiles;

import java.time.Duration;
import java.util.Objects;

/**
 * Límites de la reconciliación de huérfanos (TD-041), desde configuración.
 *
 * @param grace edad mínima de un objeto para juzgarlo: más joven puede ser una subida en curso
 * @param pageSize objetos por página del listado
 * @param scanLimit objetos que examina una pasada; la siguiente sigue donde quedó
 * @param deleteLimit freno: si una pasada halla más huérfanos que esto, no borra ninguno
 */
public record OrphanReconciliationPolicy(
    Duration grace, int pageSize, int scanLimit, int deleteLimit) {

  public OrphanReconciliationPolicy {
    Objects.requireNonNull(grace, "grace");
    if (grace.isNegative() || grace.compareTo(Duration.ofHours(1)) < 0) {
      throw new IllegalArgumentException(
          "La espera antes de juzgar un objeto no baja de una hora: una subida lenta no es huérfana");
    }
    if (pageSize <= 0 || scanLimit < pageSize || deleteLimit <= 0) {
      throw new IllegalArgumentException("Límites de reconciliación no válidos");
    }
  }
}
