package com.usac.jobseeker.auth.application.usecase.provisionaccount;

import com.usac.jobseeker.auth.domain.model.Account;
import java.util.Objects;
import org.springframework.transaction.annotation.Transactional;

/** Se registra como bean en `infrastructure.config`, sin estereotipos aquí (ADR-0015, ARCH-02). */
public class ProvisionAccountService implements ProvisionAccountUseCase {

  private final AccountProvisioningPort accounts;

  public ProvisionAccountService(AccountProvisioningPort accounts) {
    this.accounts = Objects.requireNonNull(accounts, "accounts");
  }

  // El caso de uso es la frontera transaccional (ADR-0016): crear y releer ven la misma fila.
  @Override
  @Transactional
  public ProvisionAccountResult execute(ProvisionAccountCommand command) {
    boolean created = accounts.insertIfAbsent(command.accountId(), Account.initialCapabilities());
    Account account =
        accounts
            .findById(command.accountId())
            .orElseThrow(
                () -> new IllegalStateException("la cuenta " + command.accountId() + " no existe"));
    return new ProvisionAccountResult(account.toActor(), account.deletionStatus(), created);
  }
}
