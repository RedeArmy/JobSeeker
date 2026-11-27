package com.usac.jobseeker.ats.domain.model;

import java.util.Objects;
import java.util.UUID;

/**
 * Identidad de una postulación. Es opaca para los demás módulos (`matching` la guarda tal cual).
 */
public record ApplicationId(UUID value) {

  public ApplicationId {
    Objects.requireNonNull(value, "value");
  }

  @Override
  public String toString() {
    return value.toString();
  }
}
