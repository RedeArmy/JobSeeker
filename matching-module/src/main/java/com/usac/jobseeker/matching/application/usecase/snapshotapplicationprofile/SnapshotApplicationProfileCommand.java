package com.usac.jobseeker.matching.application.usecase.snapshotapplicationprofile;

import com.usac.jobseeker.common.domain.model.AccountId;
import com.usac.jobseeker.matching.domain.model.JobPostingId;
import com.usac.jobseeker.matching.domain.model.SnapshotReason;
import java.util.Objects;
import java.util.UUID;

/**
 * Guardar la foto de una postulación: el puntaje y, si es la postulación, la copia del perfil
 * canónico. La llama `ats` cuando exista; `applicationId` es el identificador opaco de su
 * postulación.
 */
public record SnapshotApplicationProfileCommand(
    AccountId candidate, JobPostingId job, UUID applicationId, SnapshotReason reason) {

  public SnapshotApplicationProfileCommand {
    Objects.requireNonNull(candidate, "candidate");
    Objects.requireNonNull(job, "job");
    Objects.requireNonNull(applicationId, "applicationId");
    Objects.requireNonNull(reason, "reason");
  }
}
