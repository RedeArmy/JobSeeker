package com.usac.jobseeker.job.application.usecase.updatejobposting;

import com.usac.jobseeker.common.domain.model.AccountId;
import com.usac.jobseeker.job.application.support.JobChange;
import com.usac.jobseeker.job.application.support.JobDetailsInput;
import com.usac.jobseeker.job.domain.model.JobPostingId;
import java.util.Objects;

/** Editar una vacante propia, con la versión que el cliente leyó. */
public record UpdateJobPostingCommand(
    AccountId owner, JobPostingId id, long expectedVersion, JobDetailsInput details) {

  public UpdateJobPostingCommand {
    Objects.requireNonNull(owner, "owner");
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(details, "details");
    JobChange.requireValidVersion(expectedVersion);
  }
}
