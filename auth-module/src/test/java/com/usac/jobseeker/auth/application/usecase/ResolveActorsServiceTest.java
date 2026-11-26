package com.usac.jobseeker.auth.application.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.usac.jobseeker.auth.application.usecase.resolveactor.ActorResolution;
import com.usac.jobseeker.auth.application.usecase.resolveactor.ActorResolution.Reason;
import com.usac.jobseeker.auth.application.usecase.resolveactor.ResolveActorQuery;
import com.usac.jobseeker.auth.application.usecase.resolveactor.ResolveActorService;
import com.usac.jobseeker.auth.application.usecase.resolveactor.ResolveActorsQuery;
import com.usac.jobseeker.auth.application.usecase.resolveactor.ResolveActorsService;
import com.usac.jobseeker.auth.domain.model.Account;
import com.usac.jobseeker.auth.domain.model.DeletionStatus;
import com.usac.jobseeker.common.domain.model.AccountId;
import com.usac.jobseeker.common.domain.model.Capability;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** TD-039: resolver varias cuentas aplica exactamente la misma regla que resolver una. */
class ResolveActorsServiceTest {

  private final InMemoryAccounts accounts = new InMemoryAccounts();
  private final ResolveActorsService batch = new ResolveActorsService(accounts);
  private final ResolveActorService single = new ResolveActorService(accounts);

  private AccountId stored(DeletionStatus status, Capability... capabilities) {
    AccountId id = new AccountId(UUID.randomUUID());
    accounts.put(new Account(id, Set.of(capabilities), status));
    return id;
  }

  @Test
  void everyAccountResolvesToWhatTheSingleResolutionSays() {
    var active = stored(DeletionStatus.ACTIVE, Capability.CANDIDATE);
    var pending = stored(DeletionStatus.PENDING_DELETION, Capability.CANDIDATE);
    var anonymized = stored(DeletionStatus.ANONYMIZED, Capability.CANDIDATE);
    var unknown = new AccountId(UUID.randomUUID());

    var resolved =
        batch.execute(new ResolveActorsQuery(List.of(active, pending, anonymized, unknown)));

    assertThat(resolved).containsOnlyKeys(active, pending, anonymized, unknown);
    for (AccountId id : List.of(active, pending, anonymized, unknown)) {
      assertThat(resolved).containsEntry(id, single.execute(new ResolveActorQuery(id)));
    }
    assertThat(resolved)
        .containsEntry(unknown, new ActorResolution.Rejected(Reason.NOT_PROVISIONED));
    assertThat(resolved)
        .containsEntry(pending, new ActorResolution.Rejected(Reason.PENDING_DELETION));
  }

  @Test
  void anEmptyBatchResolvesToNothing() {
    assertThat(batch.execute(new ResolveActorsQuery(List.of()))).isEmpty();
  }

  @Test
  void theQueryNeedsItsAccounts() {
    assertThatThrownBy(() -> new ResolveActorsQuery(null)).isInstanceOf(NullPointerException.class);
  }
}
