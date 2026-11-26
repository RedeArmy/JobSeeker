package com.usac.jobseeker.cv.application.usecase.getvisibleavailability;

import com.usac.jobseeker.common.domain.model.AccountId;
import java.util.List;
import java.util.Objects;

/** La disponibilidad de varios candidatos, tal como la vería la cuenta `viewer` (BR-CAND-05). */
public record GetVisibleAvailabilitiesQuery(AccountId viewer, List<AccountId> candidates) {

  public GetVisibleAvailabilitiesQuery {
    Objects.requireNonNull(viewer, "viewer");
    Objects.requireNonNull(candidates, "candidates");
    candidates = List.copyOf(candidates);
  }
}
