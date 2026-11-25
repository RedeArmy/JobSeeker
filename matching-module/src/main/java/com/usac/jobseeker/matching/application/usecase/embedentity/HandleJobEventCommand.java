package com.usac.jobseeker.matching.application.usecase.embedentity;

import com.usac.jobseeker.matching.domain.model.JobPostingId;
import java.util.Objects;

/** Un evento de `job` ya reducido a lo único que necesita `matching`: qué pasó y a qué vacante. */
public record HandleJobEventCommand(JobEventKind kind, JobPostingId jobId) {

  public HandleJobEventCommand {
    Objects.requireNonNull(kind, "kind");
    Objects.requireNonNull(jobId, "jobId");
  }
}
