package com.usac.jobseeker.cv.domain.model;

import java.util.Objects;
import java.util.UUID;

/** Identificador de un elemento de una colección del perfil (experiencia, formación, etc.). */
public record ProfileItemId(UUID value) {

  public ProfileItemId {
    Objects.requireNonNull(value, "value");
  }

  @Override
  public String toString() {
    return value.toString();
  }
}
