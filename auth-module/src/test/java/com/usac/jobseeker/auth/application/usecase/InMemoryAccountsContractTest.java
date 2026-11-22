package com.usac.jobseeker.auth.application.usecase;

import com.usac.jobseeker.auth.application.usecase.provisionaccount.AccountProvisioningPort;
import com.usac.jobseeker.auth.application.usecase.resolveactor.ActorLookupPort;

/**
 * El almacén en memoria cumple el contrato: por eso las pruebas de los casos de uso son fiables.
 */
class InMemoryAccountsContractTest extends AccountStoreContract {

  private final InMemoryAccounts accounts = new InMemoryAccounts();

  @Override
  protected AccountProvisioningPort provisioning() {
    return accounts;
  }

  @Override
  protected ActorLookupPort lookup() {
    return accounts;
  }
}
