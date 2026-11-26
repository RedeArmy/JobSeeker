package com.usac.jobseeker.matching.domain.model;

import java.util.Objects;

/**
 * El puntaje de compatibilidad de un par candidato-vacante de 0 a 100 (BR-MATCH-01) con su desglose
 * y la versión del algoritmo que lo produjo (BR-MATCH-04). `calibrated` es falso mientras no
 * existan los parámetros del protocolo de 300 pares: el valor se muestra, marcado como no calibrado
 * (ADR-0017, decisión 5).
 */
public record CompatibilityScore(
    double overall, ScoreBreakdown breakdown, AlgorithmVersion version, boolean calibrated) {

  public CompatibilityScore {
    Objects.requireNonNull(breakdown, "breakdown");
    Objects.requireNonNull(version, "version");
    if (Double.isNaN(overall) || overall < 0 || overall > 100) {
      throw new IllegalArgumentException("el puntaje va de 0 a 100");
    }
  }
}
