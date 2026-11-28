package com.usac.jobseeker.tracker.infrastructure.adapter.in.web;

import com.usac.jobseeker.tracker.application.usecase.listmyapplications.MyApplication;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Una postulación en el tracker. El estado y la columna van como códigos; la pantalla los muestra
 * en español (Recibida, En revisión, Entrevista, Oferta; Contratado, No continúa, Retirada),
 * BR-TRACK-03. Nunca lleva el motivo de un descarte.
 */
@Schema(name = "MyApplication", description = "Una postulación propia en el tracker.")
public record MyApplicationResponse(
    UUID id,
    @Schema(description = "La vacante; sigue apareciendo si se cerró o se eliminó (BR-JOB-09).")
        Job job,
    @Schema(allowableValues = {"ACTIVE", "HIRED", "REJECTED", "WITHDRAWN"}) String status,
    @Schema(
            description = "La columna pública del tablero.",
            allowableValues = {"NEW", "REVIEW", "INTERVIEW", "OFFER"})
        String stage,
    long version,
    Instant appliedAt,
    Instant updatedAt,
    @Schema(description = "Cada cambio, del primero (postular) al último (BR-TRACK-02).")
        List<Change> history) {

  /** La vacante, con su estado para marcarla si ya no está abierta. */
  @Schema(name = "MyApplicationJob")
  public record Job(
      UUID id,
      String title,
      @Schema(allowableValues = {"PUBLISHED", "CLOSED", "ARCHIVED", "DELETED"}) String status) {}

  /** Un cambio del historial; el primero no tiene origen. */
  @Schema(name = "MyApplicationChange")
  public record Change(
      String fromStatus,
      String fromStage,
      String toStatus,
      String toStage,
      @Schema(allowableValues = {"CANDIDATE", "RECRUITER", "SYSTEM"}) String by,
      Instant changedAt) {}

  static MyApplicationResponse from(MyApplication a) {
    return new MyApplicationResponse(
        a.applicationId(),
        new Job(a.jobPostingId(), a.jobTitle(), a.jobStatus()),
        a.status(),
        a.stage(),
        a.version(),
        a.appliedAt(),
        a.updatedAt(),
        a.history().stream()
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
