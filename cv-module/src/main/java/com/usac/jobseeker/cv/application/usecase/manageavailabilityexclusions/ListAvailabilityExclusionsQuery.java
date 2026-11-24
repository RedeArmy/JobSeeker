package com.usac.jobseeker.cv.application.usecase.manageavailabilityexclusions;

import com.usac.jobseeker.common.domain.model.AccountId;
import java.util.Objects;

/** Quién pregunta: siempre la propia cuenta, nunca un identificador que llegue de fuera. */
public record ListAvailabilityExclusionsQuery(AccountId accountId) {

  public ListAvailabilityExclusionsQuery {
    Objects.requireNonNull(accountId, "accountId");
  }
}
