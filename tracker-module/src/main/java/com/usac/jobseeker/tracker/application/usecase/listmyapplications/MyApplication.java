package com.usac.jobseeker.tracker.application.usecase.listmyapplications;

import com.usac.jobseeker.tracker.application.support.CandidateApplicationsPort;
import com.usac.jobseeker.tracker.application.support.PostingSummariesPort;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Una postulación en el tracker del candidato: la vacante (también si se cerró o se eliminó,
 * BR-JOB-09), el estado, la columna pública y el historial (BR-TRACK-02 y 03).
 *
 * @param jobTitle nulo solo si `job` ya no conoce la vacante, lo que no debería ocurrir: su fila
 *     nunca se borra
 * @param jobStatus `PUBLISHED`, `CLOSED`, `ARCHIVED` o `DELETED`; nulo junto con el título
 */
public record MyApplication(
    UUID applicationId,
    UUID jobPostingId,
    String jobTitle,
    String jobStatus,
    String status,
    String stage,
    long version,
    Instant appliedAt,
    Instant updatedAt,
    List<CandidateApplicationsPort.Change> history) {

  public MyApplication {
    history = List.copyOf(history);
  }

  public static MyApplication of(
      CandidateApplicationsPort.Tracked application, PostingSummariesPort.Summary posting) {
    return new MyApplication(
        application.applicationId(),
        application.jobPostingId(),
        posting == null ? null : posting.title(),
        posting == null ? null : posting.status(),
        application.status(),
        application.stage(),
        application.version(),
        application.appliedAt(),
        application.updatedAt(),
        application.history());
  }
}
