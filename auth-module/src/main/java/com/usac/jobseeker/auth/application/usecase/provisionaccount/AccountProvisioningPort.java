package com.usac.jobseeker.auth.application.usecase.provisionaccount;

import com.usac.jobseeker.auth.domain.model.Account;
import com.usac.jobseeker.common.domain.model.AccountId;
import com.usac.jobseeker.common.domain.model.Capability;
import java.util.Optional;
import java.util.Set;

/** Puerto de salida de ProvisionAccount: el almacén de cuentas locales. */
public interface AccountProvisioningPort {

  /**
   * Crea la cuenta si no existe y devuelve si la creó. Debe ser atómico: dos primeros logins
   * simultáneos de la misma identidad no pueden fallar ni crear dos filas.
   */
  boolean insertIfAbsent(AccountId id, Set<Capability> capabilities);

  Optional<Account> findById(AccountId id);
}
