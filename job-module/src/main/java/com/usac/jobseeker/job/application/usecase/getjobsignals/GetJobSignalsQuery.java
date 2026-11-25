package com.usac.jobseeker.job.application.usecase.getjobsignals;

import java.util.Objects;
import java.util.UUID;

/**
 * Leer lo que `matching` necesita de una vacante. Es una lectura interna, sin cuenta que actúe.
 * Lleva el identificador como `UUID` y no como un tipo de dominio de `job`: la superficie pública
 * de un módulo no expone su dominio, y quien la usa no tiene que construir tipos que no son suyos
 * (ARCH-04).
 */
public record GetJobSignalsQuery(UUID jobPostingId) {

  public GetJobSignalsQuery {
    Objects.requireNonNull(jobPostingId, "jobPostingId");
  }
}
