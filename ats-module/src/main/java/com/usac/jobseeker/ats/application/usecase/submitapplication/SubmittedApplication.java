package com.usac.jobseeker.ats.application.usecase.submitapplication;

import com.usac.jobseeker.ats.domain.model.Application;
import java.time.Instant;
import java.util.UUID;

/**
 * La postulación enviada, tal como la ve el candidato: sin el CV ni el perfil internos, y con el
 * estado y la columna públicos como texto (BR-TRACK-03).
 */
public record SubmittedApplication(
    UUID applicationId,
    UUID jobPostingId,
    String status,
    String stage,
    long version,
    Instant appliedAt) {

  public static SubmittedApplication of(Application application) {
    return new SubmittedApplication(
        application.id().value(),
        application.jobPostingId(),
        application.status().name(),
        application.stage().name(),
        application.version(),
        application.appliedAt());
  }
}
