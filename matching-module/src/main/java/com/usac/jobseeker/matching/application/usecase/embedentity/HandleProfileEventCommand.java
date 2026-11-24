package com.usac.jobseeker.matching.application.usecase.embedentity;

import com.usac.jobseeker.common.domain.model.AccountId;
import java.util.Objects;

/** Un evento de `cv` ya reducido a lo único que necesita `matching`: qué pasó y a qué cuenta. */
public record HandleProfileEventCommand(ProfileEventKind kind, AccountId accountId) {

  public HandleProfileEventCommand {
    Objects.requireNonNull(kind, "kind");
    Objects.requireNonNull(accountId, "accountId");
  }
}
