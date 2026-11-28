package com.usac.jobseeker.ats.application.usecase.getapplication;

import com.usac.jobseeker.ats.application.support.ApplicantProfilesPort;
import com.usac.jobseeker.ats.application.support.ApplicantScoresPort;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Lo que el reclutador dueño de la vacante ve de una postulación (TDD §4.1): el candidato **con su
 * contacto**, que al postular deja de estar enmascarado (REQ-C-07), el puntaje con su desglose y el
 * de postular, y el historial completo con los motivos de descarte (REQ-R-03).
 *
 * @param candidate nulo solo si `cv` ya no tiene el perfil, lo que no debería ocurrir
 */
public record ApplicationDetail(
    UUID applicationId,
    UUID jobPostingId,
    ApplicantProfilesPort.Profile candidate,
    String status,
    String stage,
    String rejectionReason,
    long version,
    Instant appliedAt,
    Instant updatedAt,
    ApplicantScoresPort.Score score,
    List<Change> history) {

  public ApplicationDetail {
    history = List.copyOf(history);
  }

  /** Un cambio con quién lo hizo y, si fue un descarte, su motivo. */
  public record Change(
      String fromStatus,
      String fromStage,
      String toStatus,
      String toStage,
      String by,
      String rejectionReason,
      Instant changedAt) {}
}
