package com.usac.jobseeker.matching.domain.model;

import java.util.List;
import java.util.Objects;

/**
 * El desglose explicable de un puntaje (BR-MATCH-02): una fila por dimensión, la afinidad semántica
 * calibrada con su `α` **visible** y la completitud. El coseno bruto no está aquí (ADR-0017).
 *
 * @param dimensions las seis dimensiones, incluidas las excluidas, con su estado
 * @param structuredPresent hay al menos una dimensión disponible; si no, el puntaje es solo
 *     semántico
 * @param structured puntaje de la capa estructurada en [0, 1] (0 si no hay)
 * @param semantic afinidad semántica calibrada en [0, 1]
 * @param alpha el peso efectivo de la semántica: el configurado, o 1 si no hay capa estructurada
 * @param completeness fracción del peso aplicable que el candidato declaró, en [0, 1]
 */
public record ScoreBreakdown(
    List<DimensionScore> dimensions,
    boolean structuredPresent,
    double structured,
    double semantic,
    double alpha,
    double completeness) {

  public ScoreBreakdown {
    Objects.requireNonNull(dimensions, "dimensions");
    dimensions = List.copyOf(dimensions);
  }
}
