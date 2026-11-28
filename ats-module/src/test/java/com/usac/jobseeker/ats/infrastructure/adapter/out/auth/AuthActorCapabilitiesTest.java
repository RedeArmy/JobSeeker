package com.usac.jobseeker.ats.infrastructure.adapter.out.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.usac.jobseeker.auth.application.usecase.resolveactor.ActorResolution;
import com.usac.jobseeker.common.domain.model.AccountId;
import com.usac.jobseeker.common.domain.model.Actor;
import com.usac.jobseeker.common.domain.model.Capability;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** `ats` pregunta a `auth` por las capacidades con su caso de uso, nunca por su tabla. */
class AuthActorCapabilitiesTest {

  private final AccountId account = new AccountId(UUID.randomUUID());

  private AuthActorCapabilities answering(ActorResolution resolution) {
    return new AuthActorCapabilities(
        query -> {
          assertThat(query.accountId()).isEqualTo(account);
          return resolution;
        });
  }

  @Test
  void eachCapabilityIsAnsweredOnItsOwn() {
    var candidate =
        answering(new ActorResolution.Resolved(new Actor(account, Set.of(Capability.CANDIDATE))));
    var recruiter =
        answering(new ActorResolution.Resolved(new Actor(account, Set.of(Capability.RECRUITER))));

    assertThat(candidate.isCandidate(account)).isTrue();
    assertThat(candidate.isRecruiter(account)).isFalse();
    assertThat(recruiter.isRecruiter(account)).isTrue();
    assertThat(recruiter.isCandidate(account)).isFalse();
  }

  // BR-DATA-04: una cuenta que no puede actuar (p. ej. en periodo de gracia) no tiene ninguna.
  @Test
  void anAccountThatCannotActHasNoCapability() {
    for (ActorResolution.Reason reason : ActorResolution.Reason.values()) {
      var adapter = answering(new ActorResolution.Rejected(reason));

      assertThat(adapter.isCandidate(account)).isFalse();
      assertThat(adapter.isRecruiter(account)).isFalse();
    }
  }
}
