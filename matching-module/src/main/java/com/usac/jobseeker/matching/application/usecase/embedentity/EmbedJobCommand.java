package com.usac.jobseeker.matching.application.usecase.embedentity;

import com.usac.jobseeker.matching.domain.model.JobPostingId;
import java.util.Objects;

/** Qué vacante se calcula. El estado que se embebe se lee al ejecutar, no viaja aquí. */
public record EmbedJobCommand(JobPostingId jobId) {

  public EmbedJobCommand {
    Objects.requireNonNull(jobId, "jobId");
  }
}
