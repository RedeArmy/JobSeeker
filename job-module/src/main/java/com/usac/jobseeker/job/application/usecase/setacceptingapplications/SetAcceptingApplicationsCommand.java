package com.usac.jobseeker.job.application.usecase.setacceptingapplications;

import com.usac.jobseeker.common.domain.model.AccountId;
import com.usac.jobseeker.job.application.support.JobChange;
import com.usac.jobseeker.job.domain.model.JobPostingId;
import java.util.Objects;

/** Pausar o reanudar la recepción de postulaciones de una vacante publicada (BR-JOB-10). */
public record SetAcceptingApplicationsCommand(
    AccountId owner, JobPostingId id, boolean accepting, long expectedVersion) {

  public SetAcceptingApplicationsCommand {
    Objects.requireNonNull(owner, "owner");
    Objects.requireNonNull(id, "id");
    JobChange.requireValidVersion(expectedVersion);
  }
}
