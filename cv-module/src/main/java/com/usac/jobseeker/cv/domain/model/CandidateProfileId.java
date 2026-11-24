package com.usac.jobseeker.cv.domain.model;

import java.util.Objects;
import java.util.UUID;

/** Identificador del perfil de candidato. Es propio de `cv`: no se confunde con el de la cuenta. */
public record CandidateProfileId(UUID value) {

  public CandidateProfileId {
    Objects.requireNonNull(value, "value");
  }

  @Override
  public String toString() {
    return value.toString();
  }
}
