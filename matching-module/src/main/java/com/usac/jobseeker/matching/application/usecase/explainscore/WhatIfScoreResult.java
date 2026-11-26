package com.usac.jobseeker.matching.application.usecase.explainscore;

import com.usac.jobseeker.matching.domain.model.CompatibilityScore;
import java.util.Objects;

/**
 * El puntaje de hoy y el hipotético con lo que el candidato probó, calculados con el mismo
 * agregador. Nada se guarda.
 *
 * @param current presente solo si `status` es `OK`
 * @param hypothetical presente solo si `status` es `OK`
 */
public record WhatIfScoreResult(
    Status status, CompatibilityScore current, CompatibilityScore hypothetical) {

  public enum Status {
    OK,
    PENDING_EMBEDDING
  }

  public WhatIfScoreResult {
    Objects.requireNonNull(status, "status");
  }
}
