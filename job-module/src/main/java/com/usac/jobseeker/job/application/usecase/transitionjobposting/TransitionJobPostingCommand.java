package com.usac.jobseeker.job.application.usecase.transitionjobposting;

import com.usac.jobseeker.common.domain.model.AccountId;
import com.usac.jobseeker.job.application.support.JobChange;
import com.usac.jobseeker.job.domain.model.JobPostingId;
import com.usac.jobseeker.job.domain.model.JobStatus;
import java.util.Objects;

/** Pedir una transición manual de la vacante, con la versión que el cliente leyó (BR-JOB-03). */
public record TransitionJobPostingCommand(
    AccountId owner, JobPostingId id, JobStatus to, long expectedVersion) {

  public TransitionJobPostingCommand {
    Objects.requireNonNull(owner, "owner");
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(to, "to");
    JobChange.requireValidVersion(expectedVersion);
  }
}
