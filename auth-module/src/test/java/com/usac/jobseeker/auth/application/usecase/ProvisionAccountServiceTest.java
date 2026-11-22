package com.usac.jobseeker.auth.application.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.usac.jobseeker.auth.application.usecase.provisionaccount.AccountProvisioningPort;
import com.usac.jobseeker.auth.application.usecase.provisionaccount.ProvisionAccountCommand;
import com.usac.jobseeker.auth.application.usecase.provisionaccount.ProvisionAccountResult;
import com.usac.jobseeker.auth.application.usecase.provisionaccount.ProvisionAccountService;
import com.usac.jobseeker.auth.domain.model.Account;
import com.usac.jobseeker.auth.domain.model.DeletionStatus;
import com.usac.jobseeker.common.domain.model.AccountId;
import com.usac.jobseeker.common.domain.model.Capability;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ProvisionAccountServiceTest {

  private final InMemoryAccounts accounts = new InMemoryAccounts();
  private final ProvisionAccountService service = new ProvisionAccountService(accounts);

  @Test
  void theFirstLoginCreatesTheAccountWithTheCandidateCapabilityOnly() {
    AccountId id = new AccountId(UUID.randomUUID());

    ProvisionAccountResult result = service.execute(new ProvisionAccountCommand(id));

    // BR-ACCT-09: la de reclutador no se concede al registrarse, exige verificación.
    assertThat(result.created()).isTrue();
    assertThat(result.actor().capabilities()).containsExactly(Capability.CANDIDATE);
    assertThat(result.actor().id()).isEqualTo(id);
    assertThat(result.deletionStatus()).isEqualTo(DeletionStatus.ACTIVE);
  }

  @Test
  void aSecondLoginChangesNothing() {
    AccountId id = new AccountId(UUID.randomUUID());
    accounts.put(
        new Account(id, Set.of(Capability.CANDIDATE, Capability.RECRUITER), DeletionStatus.ACTIVE));

    ProvisionAccountResult result = service.execute(new ProvisionAccountCommand(id));

    assertThat(result.created()).isFalse();
    // No pisa las capacidades que la cuenta ya tenía.
    assertThat(result.actor().capabilities())
        .containsExactlyInAnyOrder(Capability.CANDIDATE, Capability.RECRUITER);
    assertThat(accounts.size()).isEqualTo(1);
  }

  @Test
  void anAccountInTheGracePeriodIsReturnedAsIsSoTheCallerCanCancelTheDeletion() {
    AccountId id = new AccountId(UUID.randomUUID());
    accounts.put(new Account(id, Set.of(Capability.CANDIDATE), DeletionStatus.PENDING_DELETION));

    ProvisionAccountResult result = service.execute(new ProvisionAccountCommand(id));

    assertThat(result.created()).isFalse();
    assertThat(result.deletionStatus()).isEqualTo(DeletionStatus.PENDING_DELETION);
  }

  @Test
  void anAccountThatVanishesRightAfterBeingCreatedIsAnInconsistencyNotASilentSuccess() {
    // La lectura tras el alta ve la misma fila (ADR-0016); si no la ve, algo está roto y se dice.
    AccountId id = new AccountId(UUID.randomUUID());
    AccountProvisioningPort vanishing =
        new AccountProvisioningPort() {
          @Override
          public boolean insertIfAbsent(AccountId accountId, Set<Capability> capabilities) {
            return true;
          }

          @Override
          public Optional<Account> findById(AccountId accountId) {
            return Optional.empty();
          }
        };

    ProvisionAccountService serviceOverVanishingStore = new ProvisionAccountService(vanishing);
    ProvisionAccountCommand command = new ProvisionAccountCommand(id);

    assertThatThrownBy(() -> serviceOverVanishingStore.execute(command))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining(id.toString());
  }

  @Test
  void theServiceCannotBeBuiltWithoutItsPort() {
    assertThatThrownBy(() -> new ProvisionAccountService(null))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("accounts");
  }
}
