package com.usac.jobseeker.job.application.usecase.getjobposting;

import com.usac.jobseeker.common.domain.model.AccountId;
import com.usac.jobseeker.job.domain.model.JobPostingId;
import java.util.Objects;

/** Leer una vacante: el dueño la ve siempre; un candidato, solo si su estado es visible. */
public record GetJobPostingQuery(AccountId viewer, JobPostingId id) {

  public GetJobPostingQuery {
    Objects.requireNonNull(viewer, "viewer");
    Objects.requireNonNull(id, "id");
  }
}
