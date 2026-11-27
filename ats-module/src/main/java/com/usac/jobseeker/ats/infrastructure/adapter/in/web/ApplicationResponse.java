package com.usac.jobseeker.ats.infrastructure.adapter.in.web;

import com.usac.jobseeker.ats.application.usecase.submitapplication.SubmittedApplication;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

/**
 * La postulación tal como la ve el candidato. Sin el CV ni el perfil internos, y sin el motivo de
 * un descarte (BR-TRACK-03).
 */
@Schema(name = "Application", description = "Una postulación vista por el candidato.")
public record ApplicationResponse(
    @Schema(description = "Identificador de la postulación.") UUID id,
    @Schema(description = "La vacante a la que se postuló.") UUID jobPostingId,
    @Schema(
            description = "Si sigue viva.",
            allowableValues = {"ACTIVE", "HIRED", "REJECTED", "WITHDRAWN"})
        String status,
    @Schema(
            description = "La columna pública del tablero (BR-TRACK-03).",
            allowableValues = {"NEW", "REVIEW", "INTERVIEW", "OFFER"})
        String stage,
    @Schema(description = "Versión de la postulación.") long version,
    @Schema(description = "Cuándo se postuló.") Instant appliedAt) {

  static ApplicationResponse from(SubmittedApplication application) {
    return new ApplicationResponse(
        application.applicationId(),
        application.jobPostingId(),
        application.status(),
        application.stage(),
        application.version(),
        application.appliedAt());
  }
}
