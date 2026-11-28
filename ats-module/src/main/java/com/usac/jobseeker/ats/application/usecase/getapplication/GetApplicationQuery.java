package com.usac.jobseeker.ats.application.usecase.getapplication;

import com.usac.jobseeker.common.domain.model.AccountId;
import java.util.Objects;
import java.util.UUID;

/** El detalle de una postulación para el reclutador; quien pregunta sale del token. */
public record GetApplicationQuery(AccountId recruiter, UUID applicationId) {

  public GetApplicationQuery {
    Objects.requireNonNull(recruiter, "recruiter");
    Objects.requireNonNull(applicationId, "applicationId");
  }
}
