package com.usac.jobseeker.matching.application.usecase.calculatecompatibilityscore;

import com.usac.jobseeker.common.domain.model.AccountId;
import com.usac.jobseeker.matching.domain.model.JobPostingId;
import com.usac.jobseeker.matching.domain.model.ScoreReason;
import java.util.Objects;

/** Puntuar y guardar el par de esta cuenta candidata con esta vacante, con su motivo. */
public record CalculateCompatibilityScoreCommand(
    AccountId candidate, JobPostingId job, ScoreReason reason) {

  public CalculateCompatibilityScoreCommand {
    Objects.requireNonNull(candidate, "candidate");
    Objects.requireNonNull(job, "job");
    Objects.requireNonNull(reason, "reason");
  }
}
