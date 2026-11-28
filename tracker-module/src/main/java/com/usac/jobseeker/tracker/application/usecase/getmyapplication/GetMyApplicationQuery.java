package com.usac.jobseeker.tracker.application.usecase.getmyapplication;

import com.usac.jobseeker.common.domain.model.AccountId;
import java.util.Objects;
import java.util.UUID;

/** Una postulación propia; la cuenta sale del token. */
public record GetMyApplicationQuery(AccountId candidate, UUID applicationId) {

  public GetMyApplicationQuery {
    Objects.requireNonNull(candidate, "candidate");
    Objects.requireNonNull(applicationId, "applicationId");
  }
}
