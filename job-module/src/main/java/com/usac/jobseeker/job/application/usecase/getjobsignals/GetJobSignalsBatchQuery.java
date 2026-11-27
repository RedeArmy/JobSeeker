package com.usac.jobseeker.job.application.usecase.getjobsignals;

import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Leer las señales de varias vacantes de una vez (coding-standards §8.1): el recálculo de un perfil
 * las necesita para todos sus pares. Identificadores como `UUID`, como la consulta individual.
 */
public record GetJobSignalsBatchQuery(List<UUID> jobPostingIds) {

  public GetJobSignalsBatchQuery {
    Objects.requireNonNull(jobPostingIds, "jobPostingIds");
    jobPostingIds = List.copyOf(jobPostingIds);
  }

  public static GetJobSignalsBatchQuery of(Collection<UUID> ids) {
    return new GetJobSignalsBatchQuery(List.copyOf(ids));
  }
}
