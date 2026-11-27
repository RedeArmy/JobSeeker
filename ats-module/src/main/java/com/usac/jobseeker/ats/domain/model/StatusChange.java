package com.usac.jobseeker.ats.domain.model;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Una fila del historial (A2): cada cambio de estado o de etapa, con quién y cuándo. Es lo que el
 * candidato ve como historial (BR-TRACK-02) y lo que hace auditable un descarte (REQ-R-03), incluso
 * después de reabrirlo (D5).
 *
 * @param fromStatus nulo solo al postular, cuando la postulación no existía
 * @param fromStage nulo exactamente cuando lo es {@code fromStatus}
 * @param rejectionReason solo en un descarte
 */
public record StatusChange(
    UUID id,
    ApplicationId applicationId,
    ApplicationStatus fromStatus,
    PipelineStage fromStage,
    ApplicationStatus toStatus,
    PipelineStage toStage,
    ChangeInitiator initiator,
    RejectionReason rejectionReason,
    Instant changedAt) {

  public StatusChange {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(applicationId, "applicationId");
    Objects.requireNonNull(toStatus, "toStatus");
    Objects.requireNonNull(toStage, "toStage");
    Objects.requireNonNull(initiator, "initiator");
    Objects.requireNonNull(changedAt, "changedAt");
    if ((fromStatus == null) != (fromStage == null)) {
      throw new IllegalArgumentException("el origen viene completo o no viene");
    }
    if ((toStatus == ApplicationStatus.REJECTED) != (rejectionReason != null)) {
      throw new IllegalArgumentException("solo un descarte lleva motivo, y todo descarte lo lleva");
    }
  }
}
