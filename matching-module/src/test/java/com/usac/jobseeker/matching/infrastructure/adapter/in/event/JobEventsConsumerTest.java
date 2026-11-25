package com.usac.jobseeker.matching.infrastructure.adapter.in.event;

import static org.assertj.core.api.Assertions.assertThat;

import com.usac.jobseeker.common.application.outbox.OutboxEvent;
import com.usac.jobseeker.matching.application.usecase.embedentity.HandleJobEventCommand;
import com.usac.jobseeker.matching.application.usecase.embedentity.JobEventKind;
import com.usac.jobseeker.matching.domain.model.JobPostingId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/** Qué eventos de `job` atiende `matching` y cómo los traduce. */
class JobEventsConsumerTest {

  private final List<HandleJobEventCommand> handled = new ArrayList<>();
  private final JobEventsConsumer consumer = new JobEventsConsumer(handled::add);
  private final UUID job = UUID.randomUUID();

  private OutboxEvent event(String type, String payload) {
    return new OutboxEvent(UUID.randomUUID(), "JOB_POSTING", job, 3, type, 1, payload, "t");
  }

  @ParameterizedTest
  @CsvSource({
    "job.posting_updated,CONTENT_UPDATED",
    "job.posting_published,STATE_CHANGED",
    "job.posting_reopened,STATE_CHANGED",
    "job.posting_closed,STATE_CHANGED",
    "job.posting_archived,STATE_CHANGED",
    "job.posting_deleted,STATE_CHANGED",
    "job.recruiting_stage_changed,STATE_CHANGED"
  })
  void eachEventOfJobThatChangesAPostingIsTranslatedToItsKind(String type, JobEventKind kind) {
    assertThat(consumer.handles(type)).isTrue();

    consumer.consume(event(type, "{\"jobPostingId\":\"" + job + "\",\"version\":4}"));

    assertThat(handled).containsExactly(new HandleJobEventCommand(kind, new JobPostingId(job)));
  }

  // Los eventos de perfil son de otro consumidor: este no debe atenderlos.
  @ParameterizedTest
  @ValueSource(
      strings = {"profile.updated", "candidate.discoverability_changed", "account.suspended", ""})
  void otherEventsAreNotHandled(String type) {
    assertThat(consumer.handles(type)).isFalse();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "{}",
        "{\"jobPostingId\":\"no-es-uuid\"}",
        "{\"jobPostingId\":\"\"}",
        "no es json",
        "{\"otra\":1}"
      })
  void aPayloadWithoutAValidJobIsIgnoredNotRetriedForever(String payload) {
    consumer.consume(event("job.posting_updated", payload));

    assertThat(handled).isEmpty();
  }

  @Test
  void theNameIsStableAndDifferentFromTheProfileConsumerBecauseItKeysTheDeduplication() {
    assertThat(consumer.name()).isEqualTo("matching-job-events");
    assertThat(consumer.name()).isNotEqualTo(new ProfileEventsConsumer(c -> {}).name());
  }
}
