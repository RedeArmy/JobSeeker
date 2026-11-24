package com.usac.jobseeker.cv.infrastructure.adapter.out.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.usac.jobseeker.auth.application.usecase.resolveactor.ActorResolution;
import com.usac.jobseeker.common.domain.model.AccountId;
import com.usac.jobseeker.common.domain.model.Actor;
import com.usac.jobseeker.common.domain.model.Capability;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Solo se puede excluir a una cuenta que una persona puede volver a usar. */
class AuthAccountDirectoryTest {

  private final AccountId account = new AccountId(UUID.randomUUID());

  private AuthAccountDirectory answering(ActorResolution resolution) {
    return new AuthAccountDirectory(query -> resolution);
  }

  @Test
  void anActiveAccountIsLiveWhateverItsCapabilities() {
    assertThat(
            answering(new ActorResolution.Resolved(new Actor(account, Set.of()))).isLive(account))
        .isTrue();
    assertThat(
            answering(
                    new ActorResolution.Resolved(new Actor(account, Set.of(Capability.RECRUITER))))
                .isLive(account))
        .isTrue();
  }

  @Test
  void anAccountInItsDeletionGracePeriodIsLiveBecauseItsOwnerCanCancelTheDeletion() {
    assertThat(
            answering(new ActorResolution.Rejected(ActorResolution.Reason.PENDING_DELETION))
                .isLive(account))
        .isTrue();
  }

  @Test
  void anUnknownAnonymizedOrIdentityLessAccountIsNotLive() {
    assertThat(
            answering(new ActorResolution.Rejected(ActorResolution.Reason.NOT_PROVISIONED))
                .isLive(account))
        .isFalse();
    assertThat(
            answering(new ActorResolution.Rejected(ActorResolution.Reason.ANONYMIZED))
                .isLive(account))
        .isFalse();
    assertThat(
            answering(new ActorResolution.Rejected(ActorResolution.Reason.IDENTITY_DELETED))
                .isLive(account))
        .isFalse();
  }
}
