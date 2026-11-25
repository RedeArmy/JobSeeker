package com.usac.jobseeker.job.application.usecase.getownedjobstate;

import com.usac.jobseeker.common.domain.model.AccountId;
import java.util.Objects;
import java.util.UUID;

/**
 * Preguntar en qué estado está una vacante **propia**. Lleva el identificador como `UUID` y no como
 * un tipo de dominio de `job`: la superficie pública de un módulo no expone su dominio (ARCH-04).
 */
public record GetOwnedJobStateQuery(AccountId owner, UUID jobPostingId) {

  public GetOwnedJobStateQuery {
    Objects.requireNonNull(owner, "owner");
    Objects.requireNonNull(jobPostingId, "jobPostingId");
  }
}
