package com.usac.jobseeker.job.application.usecase.createjobposting;

import com.usac.jobseeker.common.domain.model.AccountId;
import com.usac.jobseeker.job.application.support.JobDetailsInput;
import java.util.Objects;

/** Crear una vacante en borrador. El dueño sale del token, nunca del cuerpo (BR-JOB-01). */
public record CreateJobPostingCommand(AccountId owner, JobDetailsInput details) {

  public CreateJobPostingCommand {
    Objects.requireNonNull(owner, "owner");
    Objects.requireNonNull(details, "details");
  }
}
