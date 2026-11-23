package com.usac.jobseeker.auth.application.usecase;

import static org.assertj.core.api.Assertions.assertThat;

import com.usac.jobseeker.auth.application.usecase.resolveactor.ActorResolution;
import com.usac.jobseeker.auth.application.usecase.resolveactor.ActorResolution.Reason;
import com.usac.jobseeker.auth.application.usecase.resolveactor.ResolveActorQuery;
import com.usac.jobseeker.auth.application.usecase.resolveactor.ResolveActorService;
import com.usac.jobseeker.auth.domain.model.Account;
import com.usac.jobseeker.auth.domain.model.DeletionStatus;
import com.usac.jobseeker.auth.domain.model.IdentityStatus;
import com.usac.jobseeker.common.domain.model.AccountId;
import com.usac.jobseeker.common.domain.model.Capability;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ResolveActorServiceTest {

  private final InMemoryAccounts accounts = new InMemoryAccounts();
  private final ResolveActorService service = new ResolveActorService(accounts);

  @Test
  void anActiveAccountResolvesToItsActorWithTheStoredCapabilities() {
    AccountId id = new AccountId(UUID.randomUUID());
    accounts.put(new Account(id, Set.of(Capability.RECRUITER), DeletionStatus.ACTIVE));

    ActorResolution resolution = service.execute(new ResolveActorQuery(id));

    assertThat(resolution).isInstanceOf(ActorResolution.Resolved.class);
    var actor = ((ActorResolution.Resolved) resolution).actor();
    assertThat(actor.has(Capability.RECRUITER)).isTrue();
    // Las capacidades salen de la base, no de lo que diga el token (BR-ACCT-04).
    assertThat(actor.has(Capability.CANDIDATE)).isFalse();
  }

  @Test
  void anUnknownAccountIsRejectedAsNotProvisioned() {
    ActorResolution resolution =
        service.execute(new ResolveActorQuery(new AccountId(UUID.randomUUID())));

    assertThat(resolution).isEqualTo(new ActorResolution.Rejected(Reason.NOT_PROVISIONED));
  }

  @Test
  void anAccountPendingDeletionIsRejected() {
    AccountId id = new AccountId(UUID.randomUUID());
    accounts.put(new Account(id, Set.of(Capability.CANDIDATE), DeletionStatus.PENDING_DELETION));

    assertThat(service.execute(new ResolveActorQuery(id)))
        .isEqualTo(new ActorResolution.Rejected(Reason.PENDING_DELETION));
  }

  @Test
  void anAccountWhoseIdentityWasDeletedInClavarisIsRejectedEvenIfItsOwnStatusIsActive() {
    AccountId id = new AccountId(UUID.randomUUID());
    accounts.put(
        new Account(
            id, Set.of(Capability.CANDIDATE), DeletionStatus.ACTIVE, IdentityStatus.DELETED));

    assertThat(service.execute(new ResolveActorQuery(id)))
        .isEqualTo(new ActorResolution.Rejected(Reason.IDENTITY_DELETED));
  }

  @Test
  void aSuspendedIdentityStillResolvesBecauseItsSessionsAreClosedByTheWebhookNotHere() {
    AccountId id = new AccountId(UUID.randomUUID());
    accounts.put(
        new Account(
            id, Set.of(Capability.CANDIDATE), DeletionStatus.ACTIVE, IdentityStatus.SUSPENDED));

    assertThat(service.execute(new ResolveActorQuery(id)))
        .isInstanceOf(ActorResolution.Resolved.class);
  }

  @Test
  void anAnonymizedAccountIsRejected() {
    AccountId id = new AccountId(UUID.randomUUID());
    accounts.put(new Account(id, Set.of(Capability.CANDIDATE), DeletionStatus.ANONYMIZED));

    assertThat(service.execute(new ResolveActorQuery(id)))
        .isEqualTo(new ActorResolution.Rejected(Reason.ANONYMIZED));
  }
}
