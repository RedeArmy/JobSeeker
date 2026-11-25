package com.usac.jobseeker.matching.application.usecase.suggestcandidatesforjob;

import com.usac.jobseeker.matching.domain.model.CandidateProfileId;
import java.util.List;

/**
 * Los sugeridos de una vacante, en el orden del ranking. **No lleva similitud ni puntaje**: el
 * coseno bruto no es un porcentaje de compatibilidad y nunca llega a una respuesta (ADR-0017); el
 * orden es lo único que la recuperación aporta.
 *
 * @param status si hay candidatos, si la vacante está en pausa o si aún no tiene vector
 * @param candidates los perfiles descubribles más cercanos, sin datos del candidato
 * @param remainingQuota cuántos perfiles nuevos más puede ver el reclutador en la ventana
 */
public record SuggestionsResult(
    Status status, List<CandidateProfileId> candidates, int remainingQuota) {

  /** Cómo quedó la petición. */
  public enum Status {
    OK,
    /** La vacante está en `REVIEWING`: deja de generar entradas nuevas (BR-MATCH-08). */
    PAUSED,
    /** La vacante todavía no tiene vector: aún no hay con qué recuperar. */
    PENDING_EMBEDDING
  }

  public SuggestionsResult {
    candidates = List.copyOf(candidates);
  }
}
