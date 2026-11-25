package com.usac.jobseeker.job.domain.model;

import java.util.Objects;
import java.util.UUID;

/** Identificador de la vacante. Es propio de `job`: no se confunde con el de la cuenta dueña. */
public record JobPostingId(UUID value) {

  public JobPostingId {
    Objects.requireNonNull(value, "value");
  }

  @Override
  public String toString() {
    return value.toString();
  }
}
