package com.usac.jobseeker.auth.application.usecase;

import com.usac.jobseeker.auth.application.usecase.provisionaccount.AccountProvisioningPort;
import com.usac.jobseeker.auth.application.usecase.resolveactor.ActorLookupPort;
import com.usac.jobseeker.auth.domain.model.Account;
import com.usac.jobseeker.auth.domain.model.DeletionStatus;
import com.usac.jobseeker.common.domain.model.AccountId;
import com.usac.jobseeker.common.domain.model.Capability;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Almacén en memoria para probar los casos de uso sin Spring ni base de datos. */
public class InMemoryAccounts implements AccountProvisioningPort, ActorLookupPort {

  private final Map<AccountId, Account> accounts = new ConcurrentHashMap<>();

  @Override
  public boolean insertIfAbsent(AccountId id, Set<Capability> capabilities) {
    return accounts.putIfAbsent(id, new Account(id, capabilities, DeletionStatus.ACTIVE)) == null;
  }

  @Override
  public Optional<Account> findById(AccountId id) {
    return Optional.ofNullable(accounts.get(id));
  }

  public void put(Account account) {
    accounts.put(account.id(), account);
  }

  public int size() {
    return accounts.size();
  }
}
