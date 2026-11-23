package com.usac.jobseeker.matching.domain.model;

import java.util.Objects;
import java.util.UUID;

/**
 * Identificador de la vacante: una referencia opaca a otro contexto, no una entidad de `matching`.
 */
public record JobPostingId(UUID value) {

  public JobPostingId {
    Objects.requireNonNull(value, "value");
  }

  @Override
  public String toString() {
    return value.toString();
  }
}
