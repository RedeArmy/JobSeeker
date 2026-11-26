package com.usac.jobseeker.matching.domain.model;

import java.time.Instant;
import java.util.Objects;

/** El puntaje de un par candidato-vacante tal como se guarda, con su motivo y cuándo se calculó. */
public record StoredScore(
    CandidateProfileId profile,
    JobPostingId job,
    CompatibilityScore score,
    ScoreReason reason,
    Instant computedAt) {

  public StoredScore {
    Objects.requireNonNull(profile, "profile");
    Objects.requireNonNull(job, "job");
    Objects.requireNonNull(score, "score");
    Objects.requireNonNull(reason, "reason");
    Objects.requireNonNull(computedAt, "computedAt");
  }
}
