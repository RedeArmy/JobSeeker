package com.usac.jobseeker.matching.application.usecase.embedentity;

import com.usac.jobseeker.common.domain.model.AccountId;
import java.util.Objects;

/** Qué cuenta se calcula. El estado que se embebe se lee al ejecutar, no viaja aquí. */
public record EmbedProfileCommand(AccountId accountId) {

  public EmbedProfileCommand {
    Objects.requireNonNull(accountId, "accountId");
  }
}
