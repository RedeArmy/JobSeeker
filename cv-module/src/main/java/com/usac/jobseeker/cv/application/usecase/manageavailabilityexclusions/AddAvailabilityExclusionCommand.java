package com.usac.jobseeker.cv.application.usecase.manageavailabilityexclusions;

import com.usac.jobseeker.common.domain.model.AccountId;
import java.util.Objects;

/** Excluir a una cuenta (BR-CAND-05). La clave natural es la cuenta: no lleva versión. */
public record AddAvailabilityExclusionCommand(AccountId accountId, AccountId excludedAccount) {

  public AddAvailabilityExclusionCommand {
    Objects.requireNonNull(accountId, "accountId");
    Objects.requireNonNull(excludedAccount, "excludedAccount");
  }
}
