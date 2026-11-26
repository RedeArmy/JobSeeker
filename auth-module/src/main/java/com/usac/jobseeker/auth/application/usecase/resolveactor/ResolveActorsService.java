package com.usac.jobseeker.auth.application.usecase.resolveactor;

import com.usac.jobseeker.auth.domain.model.Account;
import com.usac.jobseeker.common.domain.model.AccountId;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import org.springframework.transaction.annotation.Transactional;

/**
 * Se registra como bean en `infrastructure.config`, sin estereotipos aquí (ADR-0015, ARCH-02).
 * Resuelve varias cuentas con **una** consulta y aplica la misma regla que {@link
 * ResolveActorService}, que es el caso de una (TD-039). Es otra clase y no la misma con dos
 * interfaces porque Spring vería dos candidatos para cada tipo.
 */
public class ResolveActorsService implements ResolveActorsUseCase {

  private final ActorLookupPort accounts;

  public ResolveActorsService(ActorLookupPort accounts) {
    this.accounts = Objects.requireNonNull(accounts, "accounts");
  }

  @Override
  @Transactional(readOnly = true)
  public Map<AccountId, ActorResolution> execute(ResolveActorsQuery query) {
    Map<AccountId, Account> found = accounts.findAllById(query.accountIds());
    Map<AccountId, ActorResolution> resolved = new HashMap<>();
    for (AccountId id : query.accountIds()) {
      Account account = found.get(id);
      resolved.put(
          id,
          account == null
              ? new ActorResolution.Rejected(ActorResolution.Reason.NOT_PROVISIONED)
              : ResolveActorService.resolve(account));
    }
    return resolved;
  }
}
