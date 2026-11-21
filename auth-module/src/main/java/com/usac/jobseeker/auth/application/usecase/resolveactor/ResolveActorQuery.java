package com.usac.jobseeker.auth.application.usecase.resolveactor;

import com.usac.jobseeker.common.domain.model.AccountId;
import java.util.Objects;

/** Petición de reconstruir quién actúa a partir de la cuenta autenticada. */
public record ResolveActorQuery(AccountId accountId) {

  public ResolveActorQuery {
    Objects.requireNonNull(accountId, "accountId");
  }
}
