package com.usac.jobseeker.cv.application.usecase.manageavailabilityexclusions;

import com.usac.jobseeker.common.domain.model.AccountId;
import java.util.Objects;

/** Quitar a una cuenta de la lista de exclusión. */
public record RemoveAvailabilityExclusionCommand(AccountId accountId, AccountId excludedAccount) {

  public RemoveAvailabilityExclusionCommand {
    Objects.requireNonNull(accountId, "accountId");
    Objects.requireNonNull(excludedAccount, "excludedAccount");
  }
}
