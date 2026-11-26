package com.usac.jobseeker.matching.application.usecase.explainscore;

import com.usac.jobseeker.matching.domain.model.CompatibilityScore;
import java.util.Objects;

/**
 * El puntaje del candidato con su desglose, o el aviso de que aún no se puede calcular.
 *
 * @param score presente solo si `status` es `OK`
 * @param updating hay un cambio del perfil o de la vacante en cola: lo que se ve es el último
 *     puntaje y se va a actualizar (BR-MATCH-03). La interfaz lo dice («actualizando…») en vez de
 *     mostrar un valor viejo como si fuera el vigente
 */
public record ExplainScoreResult(Status status, CompatibilityScore score, boolean updating) {

  public enum Status {
    OK,
    /** Falta el vector del perfil o de la vacante: se calcula cuando existan, nunca a medias. */
    PENDING_EMBEDDING
  }

  public ExplainScoreResult {
    Objects.requireNonNull(status, "status");
  }
}
