package com.usac.jobseeker.ats.infrastructure.adapter.in.web;

import com.usac.jobseeker.ats.application.usecase.listcandidateapplications.CandidateApplication;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** La postulación vista por su candidato, con su historial y sin el motivo de un descarte. */
@Schema(name = "CandidateApplication", description = "Una postulación vista por el candidato.")
public record CandidateApplicationResponse(
    UUID id,
    UUID jobPostingId,
    @Schema(allowableValues = {"ACTIVE", "HIRED", "REJECTED", "WITHDRAWN"}) String status,
    @Schema(
            description = "La columna pública del tablero (BR-TRACK-03).",
            allowableValues = {"NEW", "REVIEW", "INTERVIEW", "OFFER"})
        String stage,
    long version,
    Instant appliedAt,
    Instant updatedAt,
    @Schema(description = "Cada cambio, del primero (postular) al último (BR-TRACK-02).")
        List<Change> history) {

  /** Un cambio del historial; el primero no tiene origen. */
  @Schema(name = "ApplicationChange")
  public record Change(
      String fromStatus,
      String fromStage,
      String toStatus,
      String toStage,
      @Schema(allowableValues = {"CANDIDATE", "RECRUITER", "SYSTEM"}) String by,
      Instant changedAt) {}

  static CandidateApplicationResponse from(CandidateApplication application) {
    return new CandidateApplicationResponse(
        application.applicationId(),
        application.jobPostingId(),
        application.status(),
        application.stage(),
        application.version(),
        application.appliedAt(),
        application.updatedAt(),
        application.history().stream()
            .map(
                c ->
                    new Change(
                        c.fromStatus(),
                        c.fromStage(),
                        c.toStatus(),
                        c.toStage(),
                        c.by(),
                        c.changedAt()))
            .toList());
  }
}
