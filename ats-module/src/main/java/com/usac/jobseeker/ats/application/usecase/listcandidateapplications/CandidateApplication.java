package com.usac.jobseeker.ats.application.usecase.listcandidateapplications;

import com.usac.jobseeker.ats.domain.model.Application;
import com.usac.jobseeker.ats.domain.model.StatusChange;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Una postulación tal como la ve su candidato (BR-TRACK-02 y 03): el estado, la columna pública y
 * el historial, **sin el motivo interno de un descarte**. Se arma aquí, en `ats`, para que ningún
 * consumidor pueda mostrarle al candidato lo que no debe ver por olvido.
 *
 * @param history del primer cambio (postular) al último
 */
public record CandidateApplication(
    UUID applicationId,
    UUID jobPostingId,
    String status,
    String stage,
    long version,
    Instant appliedAt,
    Instant updatedAt,
    List<Change> history) {

  public CandidateApplication {
    history = List.copyOf(history);
  }

  /**
   * Un cambio del historial, sin motivo.
   *
   * @param fromStatus nulo en el primero, al postular
   * @param fromStage nulo en el primero, al postular
   * @param by `CANDIDATE`, `RECRUITER` o `SYSTEM`
   */
  public record Change(
      String fromStatus,
      String fromStage,
      String toStatus,
      String toStage,
      String by,
      Instant changedAt) {

    static Change of(StatusChange change) {
      return new Change(
          change.fromStatus() == null ? null : change.fromStatus().name(),
          change.fromStage() == null ? null : change.fromStage().name(),
          change.toStatus().name(),
          change.toStage().name(),
          change.initiator().name(),
          change.changedAt());
    }
  }

  public static CandidateApplication of(Application application, List<StatusChange> history) {
    return new CandidateApplication(
        application.id().value(),
        application.jobPostingId(),
        application.status().name(),
        application.stage().name(),
        application.version(),
        application.appliedAt(),
        application.updatedAt(),
        history.stream().map(Change::of).toList());
  }
}
