package com.usac.jobseeker.matching.infrastructure.adapter.in.event;

import static org.assertj.core.api.Assertions.assertThat;

import com.usac.jobseeker.common.application.outbox.OutboxEvent;
import com.usac.jobseeker.common.domain.model.AccountId;
import com.usac.jobseeker.matching.application.usecase.embedentity.HandleProfileEventCommand;
import com.usac.jobseeker.matching.application.usecase.embedentity.ProfileEventKind;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/** Qué eventos de `cv` atiende `matching` y cómo los traduce. */
class ProfileEventsConsumerTest {

  private final List<HandleProfileEventCommand> handled = new ArrayList<>();
  private final ProfileEventsConsumer consumer = new ProfileEventsConsumer(handled::add);
  private final UUID account = UUID.randomUUID();

  private OutboxEvent event(String type, String payload) {
    return new OutboxEvent(
        UUID.randomUUID(), "candidate_profile", UUID.randomUUID(), 3, type, 1, payload, "t");
  }

  @ParameterizedTest
  @CsvSource({
    "profile.updated,UPDATED",
    "candidate.discoverability_changed,DISCOVERABILITY_CHANGED",
    "profile.anonymized,ANONYMIZED"
  })
  void eachEventOfCvThatChangesAProfileIsTranslatedToItsKind(String type, ProfileEventKind kind) {
    assertThat(consumer.handles(type)).isTrue();

    consumer.consume(event(type, "{\"accountId\":\"" + account + "\",\"version\":4}"));

    assertThat(handled)
        .containsExactly(new HandleProfileEventCommand(kind, new AccountId(account)));
  }

  // La disponibilidad y las exclusiones nunca recalculan un vector (BR-MATCH-07).
  @ParameterizedTest
  @ValueSource(
      strings = {
        "candidate.availability_changed",
        "candidate.availability_exclusion_changed",
        "account.suspended",
        ""
      })
  void otherEventsAreNotHandled(String type) {
    assertThat(consumer.handles(type)).isFalse();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "{}",
        "{\"accountId\":\"no-es-uuid\"}",
        "{\"accountId\":\"\"}",
        "no es json",
        "{\"otra\":1}"
      })
  void aPayloadWithoutAValidAccountIsIgnoredNotRetriedForever(String payload) {
    consumer.consume(event("profile.updated", payload));

    assertThat(handled).isEmpty();
  }

  @Test
  void theNameIsStableAndMeaningfulBecauseItKeysTheDeduplication() {
    assertThat(consumer.name()).isEqualTo("matching-profile-events");
  }
}
