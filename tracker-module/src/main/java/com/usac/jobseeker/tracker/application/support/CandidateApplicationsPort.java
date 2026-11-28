package com.usac.jobseeker.tracker.application.support;

import com.usac.jobseeker.common.domain.model.AccountId;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Las postulaciones del candidato, según `ats`. `tracker` no tiene tablas (ADR-0027) y lee siempre
 * por aquí, con tipos propios: ningún tipo de `ats` entra en su capa de aplicación (ARCH-04).
 */
public interface CandidateApplicationsPort {

  /** Una postulación, ya sin el motivo interno de un descarte (lo quita `ats`, BR-TRACK-03). */
  record Tracked(
      UUID applicationId,
      UUID jobPostingId,
      String status,
      String stage,
      long version,
      Instant appliedAt,
      Instant updatedAt,
      List<Change> history) {

    public Tracked {
      history = List.copyOf(history);
    }
  }

  /** Un cambio del historial (BR-TRACK-02); el primero no tiene origen. */
  record Change(
      String fromStatus,
      String fromStage,
      String toStatus,
      String toStage,
      String by,
      Instant changedAt) {}

  /** De la más reciente a la más antigua. */
  List<Tracked> of(AccountId candidate);
}
