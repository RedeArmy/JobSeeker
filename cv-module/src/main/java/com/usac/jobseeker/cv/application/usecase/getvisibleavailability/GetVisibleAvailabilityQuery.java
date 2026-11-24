package com.usac.jobseeker.cv.application.usecase.getvisibleavailability;

import com.usac.jobseeker.common.domain.model.AccountId;
import java.util.Objects;

/** El estado de disponibilidad de `candidate`, tal como lo vería la cuenta `viewer`. */
public record GetVisibleAvailabilityQuery(AccountId candidate, AccountId viewer) {

  public GetVisibleAvailabilityQuery {
    Objects.requireNonNull(candidate, "candidate");
    Objects.requireNonNull(viewer, "viewer");
  }
}
