package com.usac.jobseeker.job.application.usecase.getpostingsummaries;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Qué vacantes resumir. Interna, para `tracker`: las vacantes son las de las postulaciones del
 * propio candidato, que `ats` ya le asoció, así que aquí no se autoriza de nuevo.
 */
public record GetPostingSummariesQuery(List<UUID> jobPostingIds) {

  public GetPostingSummariesQuery {
    jobPostingIds = List.copyOf(Objects.requireNonNull(jobPostingIds, "jobPostingIds"));
  }
}
