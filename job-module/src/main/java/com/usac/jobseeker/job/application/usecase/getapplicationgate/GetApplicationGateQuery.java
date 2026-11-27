package com.usac.jobseeker.job.application.usecase.getapplicationgate;

import com.usac.jobseeker.common.domain.model.AccountId;
import java.util.Objects;
import java.util.UUID;

/**
 * ¿Puede esta cuenta postular a esta vacante? Lleva la vacante como `UUID` y no como un tipo de
 * dominio de `job`: la superficie pública de un módulo no expone su dominio (ARCH-04).
 */
public record GetApplicationGateQuery(AccountId applicant, UUID jobPostingId) {

  public GetApplicationGateQuery {
    Objects.requireNonNull(applicant, "applicant");
    Objects.requireNonNull(jobPostingId, "jobPostingId");
  }
}
