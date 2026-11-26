package com.usac.jobseeker.auth.application.usecase.resolveactor;

import com.usac.jobseeker.common.domain.model.AccountId;
import java.util.List;
import java.util.Objects;

/**
 * Petición de reconstruir quién actúa para **varias** cuentas a la vez (ver {@link
 * ResolveActorsUseCase}).
 */
public record ResolveActorsQuery(List<AccountId> accountIds) {

  public ResolveActorsQuery {
    Objects.requireNonNull(accountIds, "accountIds");
    accountIds = List.copyOf(accountIds);
  }
}
