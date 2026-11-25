package com.usac.jobseeker.auth.application.usecase.getrecruiterverification;

import com.usac.jobseeker.common.domain.model.AccountId;
import java.util.Objects;

/** Preguntar la verificación de una cuenta reclutadora. Es una lectura entre módulos. */
public record GetRecruiterVerificationQuery(AccountId accountId) {

  public GetRecruiterVerificationQuery {
    Objects.requireNonNull(accountId, "accountId");
  }
}
