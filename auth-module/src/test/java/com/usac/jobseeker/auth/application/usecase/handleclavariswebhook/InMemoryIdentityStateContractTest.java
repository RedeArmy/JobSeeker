package com.usac.jobseeker.auth.application.usecase.handleclavariswebhook;

import com.usac.jobseeker.common.domain.model.AccountId;
import java.util.UUID;

/** El almacén en memoria cumple el contrato: por eso las pruebas del caso de uso son fiables. */
class InMemoryIdentityStateContractTest extends IdentityStateContract {

  private final InMemoryIdentityState state = new InMemoryIdentityState();

  @Override
  protected IdentityStatePort port() {
    return state;
  }

  @Override
  protected AccountId newAccount() {
    AccountId id = new AccountId(UUID.randomUUID());
    state.create(id);
    return id;
  }
}
