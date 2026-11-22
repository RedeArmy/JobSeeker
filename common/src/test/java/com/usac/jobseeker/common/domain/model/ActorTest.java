package com.usac.jobseeker.common.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.EnumSet;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Quién actúa (ADR-0020, BR-ACCT-04): cada capacidad se exige de forma explícita, y un actor no
 * puede cambiar después de construido.
 */
class ActorTest {

  private static final AccountId ID = new AccountId(UUID.randomUUID());

  @Test
  void anActorHasExactlyTheCapabilitiesItWasGiven() {
    Actor candidate = new Actor(ID, EnumSet.of(Capability.CANDIDATE));

    assertThat(candidate.has(Capability.CANDIDATE)).isTrue();
    assertThat(candidate.has(Capability.RECRUITER)).isFalse();
  }

  @Test
  void bothCapabilitiesCanCoexistBecauseTheyAreNotExclusive() {
    Actor both = new Actor(ID, EnumSet.allOf(Capability.class));

    assertThat(both.has(Capability.CANDIDATE)).isTrue();
    assertThat(both.has(Capability.RECRUITER)).isTrue();
  }

  @Test
  void anActorWithNoCapabilitiesHasNone() {
    Actor nobody = new Actor(ID, Set.of());

    for (Capability capability : Capability.values()) {
      assertThat(nobody.has(capability)).isFalse();
    }
  }

  @Test
  void changingTheSetThatWasPassedInDoesNotChangeTheActor() {
    Set<Capability> given = new HashSet<>(Set.of(Capability.CANDIDATE));
    Actor actor = new Actor(ID, given);

    given.add(Capability.RECRUITER);

    assertThat(actor.has(Capability.RECRUITER)).isFalse();
  }

  @Test
  void theCapabilitiesOfAnActorCannotBeModifiedFromOutside() {
    Actor actor = new Actor(ID, Set.of(Capability.CANDIDATE));

    assertThatThrownBy(() -> actor.capabilities().add(Capability.RECRUITER))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void actorsWithTheSameIdAndCapabilitiesAreEqual() {
    assertThat(new Actor(ID, Set.of(Capability.CANDIDATE)))
        .isEqualTo(new Actor(ID, Set.of(Capability.CANDIDATE)))
        .isNotEqualTo(new Actor(ID, Set.of(Capability.RECRUITER)));
  }

  @Test
  void anActorWithoutACapabilitySetIsRejected() {
    assertThatThrownBy(() -> new Actor(ID, null)).isInstanceOf(NullPointerException.class);
  }
}
