package com.usac.jobseeker.matching.application.usecase.snapshotapplicationprofile;

import com.usac.jobseeker.common.domain.model.AccountId;
import java.util.Objects;
import java.util.UUID;

/**
 * Guardar la foto de una postulación: el puntaje y, si es la postulación, la copia del perfil
 * canónico. La llama `ats`; `applicationId` es el identificador opaco de su postulación. Lleva la
 * vacante como `UUID` y el motivo como un enum propio, no como tipos del dominio de `matching`: la
 * superficie pública de un módulo no expone su dominio (ARCH-04), y `ats` no podría construirlos.
 */
public record SnapshotApplicationProfileCommand(
    AccountId candidate, UUID jobPostingId, UUID applicationId, Reason reason) {

  /** Cuándo toma `ats` la foto. El cambio de versión del algoritmo es interno de `matching`. */
  public enum Reason {
    /** Al postular: lo que el reclutador evalúa es lo que el candidato presentó. */
    APPLIED,
    /** Al cambiar de etapa en el pipeline. */
    STAGE_CHANGED
  }

  public SnapshotApplicationProfileCommand {
    Objects.requireNonNull(candidate, "candidate");
    Objects.requireNonNull(jobPostingId, "jobPostingId");
    Objects.requireNonNull(applicationId, "applicationId");
    Objects.requireNonNull(reason, "reason");
  }
}
