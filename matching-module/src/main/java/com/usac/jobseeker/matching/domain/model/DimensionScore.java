package com.usac.jobseeker.matching.domain.model;

import java.util.Objects;

/**
 * Una fila del desglose (BR-MATCH-02): la dimensión, su estado, su puntaje, el peso con que
 * contribuyó **ya renormalizado** entre las dimensiones disponibles (suma 1; 0 si quedó excluida) y
 * la explicación. Un puntaje nunca llega sin esta fila por dimensión.
 */
public record DimensionScore(
    Dimension dimension, DataStatus status, double score, double weight, String explanation) {

  public DimensionScore {
    Objects.requireNonNull(dimension, "dimension");
    Objects.requireNonNull(status, "status");
    Objects.requireNonNull(explanation, "explanation");
  }

  public boolean included() {
    return status == DataStatus.AVAILABLE;
  }
}
