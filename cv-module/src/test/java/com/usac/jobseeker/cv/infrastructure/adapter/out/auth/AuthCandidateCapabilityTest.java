package com.usac.jobseeker.cv.infrastructure.adapter.out.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.usac.jobseeker.auth.application.usecase.resolveactor.ActorResolution;
import com.usac.jobseeker.auth.application.usecase.resolveactor.ResolveActorQuery;
import com.usac.jobseeker.auth.application.usecase.resolveactor.ResolveActorsQuery;
import com.usac.jobseeker.common.domain.model.AccountId;
import com.usac.jobseeker.common.domain.model.Actor;
import com.usac.jobseeker.common.domain.model.Capability;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** `cv` pregunta a `auth` por la capacidad usando su caso de uso, no su tabla. */
class AuthCandidateCapabilityTest {

  private final AccountId account = new AccountId(UUID.randomUUID());
  private final List<ResolveActorQuery> asked = new ArrayList<>();
  private final List<ResolveActorsQuery> batchAsked = new ArrayList<>();

  private AuthCandidateCapability adapterAnswering(ActorResolution resolution) {
    return new AuthCandidateCapability(
        query -> {
          asked.add(query);
          return resolution;
        },
        batch -> {
          batchAsked.add(batch);
          Map<AccountId, ActorResolution> all = new HashMap<>();
          batch.accountIds().forEach(id -> all.put(id, resolution));
          return all;
        });
  }

  @Test
  void anAccountWithTheCandidateCapabilityIsACandidate() {
    var adapter =
        adapterAnswering(
            new ActorResolution.Resolved(new Actor(account, Set.of(Capability.CANDIDATE))));

    assertThat(adapter.isCandidate(account)).isTrue();
    assertThat(asked).singleElement().satisfies(q -> assertThat(q.accountId()).isEqualTo(account));
  }

  @Test
  void aRecruiterOnlyAccountIsNotACandidate() {
    var adapter =
        adapterAnswering(
            new ActorResolution.Resolved(new Actor(account, Set.of(Capability.RECRUITER))));

    assertThat(adapter.isCandidate(account)).isFalse();
  }

  @Test
  void anAccountWithBothCapabilitiesIsACandidate() {
    var adapter =
        adapterAnswering(
            new ActorResolution.Resolved(
                new Actor(account, Set.of(Capability.CANDIDATE, Capability.RECRUITER))));

    assertThat(adapter.isCandidate(account)).isTrue();
  }

  @Test
  void anAccountThatCannotActAHasNoCapability() {
    for (ActorResolution.Reason reason : ActorResolution.Reason.values()) {
      assertThat(adapterAnswering(new ActorResolution.Rejected(reason)).isCandidate(account))
          .as(reason.name())
          .isFalse();
    }
  }

  // TD-039: varias cuentas con una sola consulta a `auth`, con la misma regla que la individual.
  @Test
  void manyAccountsAreResolvedWithASingleQueryAndTheSameRule() {
    var other = new AccountId(UUID.randomUUID());
    var adapter =
        new AuthCandidateCapability(
            query -> new ActorResolution.Rejected(ActorResolution.Reason.NOT_PROVISIONED),
            batch -> {
              batchAsked.add(batch);
              return Map.of(
                  account,
                      new ActorResolution.Resolved(
                          new Actor(account, Set.of(Capability.CANDIDATE))),
                  other,
                      new ActorResolution.Resolved(new Actor(other, Set.of(Capability.RECRUITER))));
            });

    assertThat(adapter.candidatesAmong(List.of(account, other))).containsExactly(account);
    assertThat(batchAsked)
        .singleElement()
        .satisfies(q -> assertThat(q.accountIds()).containsExactly(account, other));
  }

  @Test
  void aRejectedAccountIsNeverAmongTheCandidates() {
    var adapter =
        new AuthCandidateCapability(
            query -> new ActorResolution.Rejected(ActorResolution.Reason.ANONYMIZED),
            batch ->
                Map.of(
                    account,
                    new ActorResolution.Rejected(ActorResolution.Reason.PENDING_DELETION)));

    assertThat(adapter.candidatesAmong(List.of(account))).isEmpty();
  }
}
