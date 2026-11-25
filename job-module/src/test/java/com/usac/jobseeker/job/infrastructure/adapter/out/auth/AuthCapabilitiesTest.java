package com.usac.jobseeker.job.infrastructure.adapter.out.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.usac.jobseeker.auth.application.usecase.resolveactor.ActorResolution;
import com.usac.jobseeker.auth.application.usecase.resolveactor.ResolveActorQuery;
import com.usac.jobseeker.common.domain.model.AccountId;
import com.usac.jobseeker.common.domain.model.Actor;
import com.usac.jobseeker.common.domain.model.Capability;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** `job` pregunta a `auth` por las capacidades usando su caso de uso, no su tabla. */
class AuthCapabilitiesTest {

  private final AccountId account = new AccountId(UUID.randomUUID());
  private final List<ResolveActorQuery> asked = new ArrayList<>();

  private AuthCapabilities answering(ActorResolution resolution) {
    return new AuthCapabilities(
        query -> {
          asked.add(query);
          return resolution;
        });
  }

  private ActorResolution resolved(Capability... capabilities) {
    return new ActorResolution.Resolved(new Actor(account, Set.of(capabilities)));
  }

  @Test
  void aRecruiterIsARecruiterAndNotACandidate() {
    var adapter = answering(resolved(Capability.RECRUITER));

    assertThat(adapter.isRecruiter(account)).isTrue();
    assertThat(adapter.isCandidate(account)).isFalse();
    assertThat(asked).allSatisfy(q -> assertThat(q.accountId()).isEqualTo(account));
  }

  @Test
  void aCandidateIsACandidateAndNotARecruiter() {
    var adapter = answering(resolved(Capability.CANDIDATE));

    assertThat(adapter.isCandidate(account)).isTrue();
    assertThat(adapter.isRecruiter(account)).isFalse();
  }

  @Test
  void anAccountWithBothCapabilitiesIsBoth() {
    var adapter = answering(resolved(Capability.CANDIDATE, Capability.RECRUITER));

    assertThat(adapter.isCandidate(account)).isTrue();
    assertThat(adapter.isRecruiter(account)).isTrue();
  }

  @Test
  void anAccountThatCannotActHasNoCapability() {
    for (ActorResolution.Reason reason : ActorResolution.Reason.values()) {
      var adapter = answering(new ActorResolution.Rejected(reason));

      assertThat(adapter.isRecruiter(account)).as(reason.name()).isFalse();
      assertThat(adapter.isCandidate(account)).as(reason.name()).isFalse();
    }
  }
}
