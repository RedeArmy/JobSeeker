package com.usac.jobseeker.cv.application.usecase.manageavailabilityexclusions;

import com.usac.jobseeker.cv.domain.model.AvailabilityExclusion;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Una cuenta excluida tal como la ve su titular: solo el identificador y cuándo se añadió. */
public record ExclusionView(UUID accountId, Instant createdAt) {

  public ExclusionView {
    Objects.requireNonNull(accountId, "accountId");
    Objects.requireNonNull(createdAt, "createdAt");
  }

  static ExclusionView of(AvailabilityExclusion exclusion) {
    return new ExclusionView(exclusion.excludedAccount().value(), exclusion.createdAt());
  }
}
