package com.usac.jobseeker.matching.domain.model;

import java.util.Objects;
import java.util.UUID;

/**
 * Identificador del perfil de candidato: una referencia opaca a otro contexto, no una entidad de
 * `matching`.
 */
public record CandidateProfileId(UUID value) {

  public CandidateProfileId {
    Objects.requireNonNull(value, "value");
  }

  @Override
  public String toString() {
    return value.toString();
  }
}
