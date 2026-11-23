package com.usac.jobseeker.cv.domain.model;

import java.util.Objects;
import java.util.UUID;

/** Identificador del perfil profesional. Es propio de `cv`: no se confunde con el de la cuenta. */
public record ProfessionalProfileId(UUID value) {

  public ProfessionalProfileId {
    Objects.requireNonNull(value, "value");
  }

  @Override
  public String toString() {
    return value.toString();
  }
}
