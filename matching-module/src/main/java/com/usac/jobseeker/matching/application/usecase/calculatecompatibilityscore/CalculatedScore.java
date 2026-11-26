package com.usac.jobseeker.matching.application.usecase.calculatecompatibilityscore;

import com.usac.jobseeker.matching.domain.model.StoredScore;
import java.util.Objects;

/**
 * El resultado de puntuar un par. Cuando no se puede puntuar, no hay puntaje a medias: el estado
 * dice por qué y quien llama decide qué mostrar.
 *
 * @param score presente solo si `status` es `CALCULATED`
 */
public record CalculatedScore(Status status, StoredScore score) {

  public enum Status {
    CALCULATED,
    /** La cuenta no tiene un perfil legible: sin capacidad, sin perfil o anonimizada. */
    NO_PROFILE,
    /** La vacante no existe o no es legible (borrador, eliminada). */
    JOB_UNAVAILABLE,
    /** Falta el vector del perfil o de la vacante: se calcula cuando existan, nunca a medias. */
    PENDING_EMBEDDING
  }

  public CalculatedScore {
    Objects.requireNonNull(status, "status");
  }

  public static CalculatedScore of(Status status) {
    return new CalculatedScore(status, null);
  }
}
