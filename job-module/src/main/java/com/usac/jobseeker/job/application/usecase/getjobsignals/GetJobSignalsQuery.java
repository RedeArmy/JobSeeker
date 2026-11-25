package com.usac.jobseeker.job.application.usecase.getjobsignals;

import com.usac.jobseeker.job.domain.model.JobPostingId;
import java.util.Objects;

/** Leer lo que `matching` necesita de una vacante. Es una lectura interna, sin cuenta que actúe. */
public record GetJobSignalsQuery(JobPostingId id) {

  public GetJobSignalsQuery {
    Objects.requireNonNull(id, "id");
  }
}
