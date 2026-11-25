package com.usac.jobseeker.matching.application.usecase.embedentity;

import static org.assertj.core.api.Assertions.assertThat;

import com.usac.jobseeker.matching.domain.model.JobPostingId;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Los eventos de `job` solo encolan; la ventana agrupa las ediciones y el estado no espera. */
class HandleJobEventServiceTest {

  private static final Instant NOW = Instant.parse("2026-11-25T10:00:00Z");
  private static final Duration WINDOW = Duration.ofSeconds(30);

  private record Request(EmbeddingEntity entity, UUID key, Instant notBefore) {}

  private final List<Request> requests = new ArrayList<>();
  private final List<String> metrics = new ArrayList<>();
  private final JobPostingId job = new JobPostingId(UUID.randomUUID());

  private final HandleJobEventService service =
      new HandleJobEventService(
          new EmbeddingWorkQueuePort() {
            public void request(EmbeddingEntity entity, UUID key, Instant notBefore) {
              requests.add(new Request(entity, key, notBefore));
            }

            public List<Job> claim(int limit, Duration lease, int maxAttempts) {
              throw new UnsupportedOperationException();
            }

            public void complete(Job job) {
              throw new UnsupportedOperationException();
            }

            public void retryAt(Job job, Instant notBefore) {
              throw new UnsupportedOperationException();
            }

            public void fail(Job job) {
              throw new UnsupportedOperationException();
            }

            public void discard(EmbeddingEntity entity, UUID key) {
              throw new UnsupportedOperationException();
            }
          },
          (useCase, outcome) -> metrics.add(useCase + ":" + outcome),
          Clock.fixed(NOW, ZoneOffset.UTC),
          WINDOW);

  // ADR-0017, decisión 10: diez ediciones seguidas son un solo cálculo.
  @Test
  void aContentUpdateWaitsForTheGroupingWindow() {
    service.execute(new HandleJobEventCommand(JobEventKind.CONTENT_UPDATED, job));

    assertThat(requests)
        .containsExactly(new Request(EmbeddingEntity.JOB, job.value(), NOW.plus(WINDOW)));
    assertThat(metrics).containsExactly("handle_job_event:content_updated");
  }

  // Cerrar una vacante o pasarla a REVIEWING debe notarse en segundos.
  @Test
  void aStateChangeIsRequestedWithoutWaiting() {
    service.execute(new HandleJobEventCommand(JobEventKind.STATE_CHANGED, job));

    assertThat(requests).containsExactly(new Request(EmbeddingEntity.JOB, job.value(), NOW));
    assertThat(metrics).containsExactly("handle_job_event:state_changed");
  }

  @Test
  void itAlwaysQueuesAJobNeverAProfile() {
    service.execute(new HandleJobEventCommand(JobEventKind.CONTENT_UPDATED, job));
    service.execute(new HandleJobEventCommand(JobEventKind.STATE_CHANGED, job));

    assertThat(requests).extracting(Request::entity).containsOnly(EmbeddingEntity.JOB);
  }

  @Test
  void theMetricCarriesNoIdentifierOfTheJob() {
    service.execute(new HandleJobEventCommand(JobEventKind.STATE_CHANGED, job));

    assertThat(metrics).noneMatch(m -> m.contains(job.toString()));
  }
}
