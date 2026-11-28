package com.usac.jobseeker.tracker.infrastructure.adapter.out.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.usac.jobseeker.auth.application.usecase.resolveactor.ActorResolution;
import com.usac.jobseeker.common.domain.model.AccountId;
import com.usac.jobseeker.common.domain.model.Actor;
import com.usac.jobseeker.common.domain.model.Capability;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** `tracker` pregunta a `auth` por la capacidad con su caso de uso, nunca por su tabla. */
class AuthTrackerCapabilityTest {

  private final AccountId account = new AccountId(UUID.randomUUID());

  private AuthTrackerCapability answering(ActorResolution resolution) {
    return new AuthTrackerCapability(query -> resolution);
  }

  @Test
  void onlyAnAccountThatCanActAsCandidateIsOne() {
    assertThat(
            answering(
                    new ActorResolution.Resolved(new Actor(account, Set.of(Capability.CANDIDATE))))
                .isCandidate(account))
        .isTrue();
    assertThat(
            answering(
                    new ActorResolution.Resolved(new Actor(account, Set.of(Capability.RECRUITER))))
                .isCandidate(account))
        .isFalse();
    for (ActorResolution.Reason reason : ActorResolution.Reason.values()) {
      assertThat(answering(new ActorResolution.Rejected(reason)).isCandidate(account)).isFalse();
    }
  }
}
