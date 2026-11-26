package com.usac.jobseeker.matching.application.usecase.suggestcandidatesforjob;

import com.usac.jobseeker.matching.domain.service.SuggestionRanking;
import java.time.Duration;

/**
 * Los topes de los sugeridos (REQ-R-11); son configuración, no código, porque se calibran con el
 * uso real.
 *
 * @param viewQuota perfiles únicos que un reclutador puede ver por ventana
 * @param window la ventana móvil de la cuota
 * @param maxPageSize el máximo de candidatos por petición
 * @param retrievalCap cuántos candidatos recupera la etapa 1 para puntuar en la etapa 2; con
 *     `ef_search` y la página que se muestra, es lo que el *recall* de ADR-0017 (control C1) mide
 * @param tieTolerance puntos que `OPEN_TO_WORK` puede sacarle de ventaja a otro para adelantarlo en
 *     el orden (BR-MATCH-07, ADR-0030); 1 es la resolución del porcentaje mostrado
 */
public record SuggestionsPolicy(
    int viewQuota, Duration window, int maxPageSize, int retrievalCap, double tieTolerance) {

  public SuggestionsPolicy {
    if (viewQuota < 0
        || maxPageSize < 1
        || window == null
        || window.isZero()
        || window.isNegative()
        || retrievalCap < maxPageSize
        || !(tieTolerance >= 0 && tieTolerance <= SuggestionRanking.MAX_TOLERANCE)) {
      throw new IllegalArgumentException(
          "la política de sugeridos no es coherente: cuota >= 0, página >= 1, ventana positiva,"
              + " recuperación >= página y tolerancia de empate entre 0 y "
              + SuggestionRanking.MAX_TOLERANCE);
    }
  }
}
