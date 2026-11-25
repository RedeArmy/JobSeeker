package com.usac.jobseeker.matching.application.usecase.suggestcandidatesforjob;

import java.time.Duration;

/**
 * Los topes de los sugeridos (REQ-R-11); son configuración, no código, porque se calibran con el
 * uso real.
 *
 * @param viewQuota perfiles únicos que un reclutador puede ver por ventana
 * @param window la ventana móvil de la cuota
 * @param maxPageSize el máximo de candidatos por petición
 */
public record SuggestionsPolicy(int viewQuota, Duration window, int maxPageSize) {

  public SuggestionsPolicy {
    if (viewQuota < 0
        || maxPageSize < 1
        || window == null
        || window.isZero()
        || window.isNegative()) {
      throw new IllegalArgumentException(
          "la política de sugeridos no es coherente: cuota >= 0, página >= 1 y ventana positiva");
    }
  }
}
