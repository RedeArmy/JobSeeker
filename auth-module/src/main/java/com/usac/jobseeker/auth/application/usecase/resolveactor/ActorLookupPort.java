package com.usac.jobseeker.auth.application.usecase.resolveactor;

import com.usac.jobseeker.auth.domain.model.Account;
import com.usac.jobseeker.common.domain.model.AccountId;
import java.util.Optional;

/** Puerto de salida de ResolveActor: lectura de la cuenta local por su identificador. */
public interface ActorLookupPort {

  Optional<Account> findById(AccountId id);

  /**
   * Varias cuentas con una consulta. La implementación por defecto pregunta una a una, correcta
   * pero lenta: sirve a quien simula el puerto, y el adaptador real la sustituye (TD-039).
   */
  default java.util.Map<AccountId, Account> findAllById(java.util.Collection<AccountId> ids) {
    java.util.Map<AccountId, Account> found = new java.util.HashMap<>();
    for (AccountId id : ids) {
      findById(id).ifPresent(account -> found.put(id, account));
    }
    return found;
  }
}
