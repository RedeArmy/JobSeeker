package com.usac.jobseeker.auth.application.usecase.getmyaccount;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.usac.jobseeker.auth.application.usecase.InMemoryAccounts;
import com.usac.jobseeker.auth.domain.model.Account;
import com.usac.jobseeker.auth.domain.model.DeletionStatus;
import com.usac.jobseeker.common.application.observability.UseCaseMetricsPort;
import com.usac.jobseeker.common.domain.model.AccountId;
import com.usac.jobseeker.common.domain.model.Capability;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** La plantilla de un caso de uso: lo que devuelve, qué mide y que no filtra datos ajenos. */
class GetMyAccountServiceTest {

  private final InMemoryAccounts accounts = new InMemoryAccounts();
  private final List<String> metrics = new ArrayList<>();
  private final UseCaseMetricsPort port =
      (useCase, outcome) -> metrics.add(useCase + ":" + outcome);
  private final GetMyAccountService service = new GetMyAccountService(accounts, port);

  private AccountId account(Set<Capability> capabilities) {
    AccountId id = new AccountId(UUID.randomUUID());
    accounts.put(new Account(id, capabilities, DeletionStatus.ACTIVE));
    return id;
  }

  @Test
  void anExistingAccountIsReturnedWithItsIdAndCapabilities() {
    AccountId id = account(Set.of(Capability.CANDIDATE, Capability.RECRUITER));

    Optional<MyAccountView> view = service.execute(new GetMyAccountQuery(id));

    assertThat(view).isPresent();
    assertThat(view.get().id()).isEqualTo(id);
    assertThat(view.get().capabilities())
        .containsExactlyInAnyOrder(Capability.CANDIDATE, Capability.RECRUITER);
  }

  @Test
  void eachCallerOnlyGetsTheirOwnAccount() {
    AccountId candidate = account(Set.of(Capability.CANDIDATE));
    AccountId recruiter = account(Set.of(Capability.RECRUITER));

    assertThat(service.execute(new GetMyAccountQuery(candidate)).orElseThrow().capabilities())
        .containsExactly(Capability.CANDIDATE);
    assertThat(service.execute(new GetMyAccountQuery(recruiter)).orElseThrow().capabilities())
        .containsExactly(Capability.RECRUITER);
  }

  @Test
  void anUnknownAccountYieldsNothing() {
    assertThat(service.execute(new GetMyAccountQuery(new AccountId(UUID.randomUUID())))).isEmpty();
  }

  @Test
  void theOutcomeIsRecordedAsABusinessMetricWithoutAnyIdentifier() {
    AccountId id = account(Set.of(Capability.CANDIDATE));

    service.execute(new GetMyAccountQuery(id));
    service.execute(new GetMyAccountQuery(new AccountId(UUID.randomUUID())));

    assertThat(metrics).containsExactly("get_my_account:found", "get_my_account:not_found");
  }

  @Test
  void theViewIsImmutableSoACallerCannotChangeTheCapabilitiesOfTheAccount() {
    AccountId id = account(Set.of(Capability.CANDIDATE));
    Set<Capability> exposed =
        service.execute(new GetMyAccountQuery(id)).orElseThrow().capabilities();

    assertThatThrownBy(() -> exposed.add(Capability.RECRUITER))
        .isInstanceOf(UnsupportedOperationException.class);
    assertThat(service.execute(new GetMyAccountQuery(id)).orElseThrow().capabilities())
        .containsExactly(Capability.CANDIDATE);
  }

  @Test
  void collaboratorsAndTheQueryAreRequired() {
    assertThatThrownBy(() -> new GetMyAccountService(null, port))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("accounts");
    assertThatThrownBy(() -> new GetMyAccountService(accounts, null))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("metrics");
    assertThatThrownBy(() -> new GetMyAccountQuery(null))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("accountId");
  }
}
