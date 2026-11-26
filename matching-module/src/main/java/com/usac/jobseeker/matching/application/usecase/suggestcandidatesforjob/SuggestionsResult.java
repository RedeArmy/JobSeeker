package com.usac.jobseeker.matching.application.usecase.suggestcandidatesforjob;

import com.usac.jobseeker.matching.domain.model.AlgorithmVersion;
import java.util.List;

/**
 * Los sugeridos de una vacante, en el orden final del pipeline. Cada uno lleva su puntaje
 * explicable y la respuesta, la versión del algoritmo y si está calibrado (BR-MATCH-04, ADR-0017
 * decisión 5). **El coseno bruto no aparece en ningún sitio** (ADR-0017).
 *
 * @param status si hay candidatos, si la vacante está en pausa o si aún no tiene vector
 * @param candidates los candidatos sugeridos, del mejor al peor según el orden final
 * @param remainingQuota cuántos perfiles nuevos más puede ver el reclutador en la ventana
 * @param algorithmVersion la versión con que se puntuó; nulo si no se puntuó a nadie
 * @param calibrated si el puntaje usa una calibración ajustada; falso hasta el protocolo de 300
 *     pares
 */
public record SuggestionsResult(
    Status status,
    List<SuggestedCandidate> candidates,
    int remainingQuota,
    AlgorithmVersion algorithmVersion,
    boolean calibrated) {

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
