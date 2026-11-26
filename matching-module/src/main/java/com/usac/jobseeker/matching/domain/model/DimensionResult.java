package com.usac.jobseeker.matching.domain.model;

import java.util.Objects;

/**
 * Lo que produce una dimensión antes de entrar al agregador: su estado, su puntaje de 0 a 1 (solo
 * con significado si está `AVAILABLE`) y la explicación determinista en español con la evidencia
 * citada, sin llamar a ningún modelo (ADR-0017, decisión 7).
 */
public record DimensionResult(DataStatus status, double score, String explanation) {

  public DimensionResult {
    Objects.requireNonNull(status, "status");
    Objects.requireNonNull(explanation, "explanation");
    if (Double.isNaN(score) || score < 0 || score > 1) {
      throw new IllegalArgumentException("el puntaje de una dimensión va de 0 a 1");
    }
  }

  public static DimensionResult available(double score, String explanation) {
    return new DimensionResult(DataStatus.AVAILABLE, score, explanation);
  }

  public static DimensionResult notDeclared(String explanation) {
    return new DimensionResult(DataStatus.NOT_DECLARED, 0, explanation);
  }

  public static DimensionResult notApplicable(String explanation) {
    return new DimensionResult(DataStatus.NOT_APPLICABLE, 0, explanation);
  }

  public static DimensionResult notComparable(String explanation) {
    return new DimensionResult(DataStatus.NOT_COMPARABLE, 0, explanation);
  }
}
