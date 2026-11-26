package com.usac.jobseeker.matching.domain.model;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Una foto del puntaje de un par en un momento, con el motivo por el que se tomó. */
public record ScoreSnapshot(
    UUID id,
    CandidateProfileId profile,
    JobPostingId job,
    SnapshotReason reason,
    CompatibilityScore score,
    Instant createdAt) {

  public ScoreSnapshot {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(profile, "profile");
    Objects.requireNonNull(job, "job");
    Objects.requireNonNull(reason, "reason");
    Objects.requireNonNull(score, "score");
    Objects.requireNonNull(createdAt, "createdAt");
  }
}
