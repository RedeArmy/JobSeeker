package com.usac.jobseeker.auth.application.usecase.provisionaccount;

import com.usac.jobseeker.common.domain.model.AccountId;
import java.util.Objects;

/** Petición de aprovisionar la cuenta de la identidad que acaba de iniciar sesión. */
public record ProvisionAccountCommand(AccountId accountId) {

  public ProvisionAccountCommand {
    Objects.requireNonNull(accountId, "accountId");
  }
}
