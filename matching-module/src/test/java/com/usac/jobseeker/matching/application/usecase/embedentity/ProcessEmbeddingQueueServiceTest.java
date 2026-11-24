package com.usac.jobseeker.matching.application.usecase.embedentity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.usac.jobseeker.common.domain.model.AccountId;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Cómo termina cada trabajo de la cola, y que ninguno tumba a los demás ni la pasada. */
class ProcessEmbeddingQueueServiceTest {

  private static final Instant NOW = Instant.parse("2026-11-24T10:00:00Z");

  private final List<EmbeddingWorkQueuePort.Job> ready = new ArrayList<>();
  private final List<String> outcomes = new ArrayList<>();
  private final List<String> metrics = new ArrayList<>();
  private final List<Duration> freshness = new ArrayList<>();
  private final Map<AccountId, RuntimeException> failures = new HashMap<>();
  private final EmbeddingQueuePolicy policy =
      new EmbeddingQueuePolicy(
          10, Duration.ofMinutes(5), 5, Duration.ofSeconds(5), Duration.ofMinutes(5));

  private final ProcessEmbeddingQueueService service =
      new ProcessEmbeddingQueueService(
          new EmbeddingWorkQueuePort() {
            public void request(AccountId a, Instant notBefore) {
              throw new UnsupportedOperationException();
            }

            public List<Job> claim(int limit, Duration lease, int maxAttempts) {
              return List.copyOf(ready);
            }

            public void complete(Job job) {
              outcomes.add("complete");
            }

            public void retryAt(Job job, Instant notBefore) {
              outcomes.add("retry@" + Duration.between(NOW, notBefore).toSeconds() + "s");
            }

            public void fail(Job job) {
              outcomes.add("fail");
            }

            public void discard(AccountId a) {
              throw new UnsupportedOperationException();
            }
          },
          command -> {
            RuntimeException failure = failures.get(command.accountId());
            if (failure != null) {
              throw failure;
            }
            return EmbedProfileUseCase.Result.EMBEDDED;
          },
          policy,
          (useCase, outcome) -> metrics.add(outcome),
          freshness::add,
          Clock.fixed(NOW, ZoneOffset.UTC));

  private EmbeddingWorkQueuePort.Job job(int attempts) {
    return job(attempts, NOW.minusSeconds(42));
  }

  private EmbeddingWorkQueuePort.Job job(int attempts, Instant pendingSince) {
    var account = new AccountId(UUID.randomUUID());
    var job = new EmbeddingWorkQueuePort.Job(UUID.randomUUID(), account, 1, attempts, pendingSince);
    ready.add(job);
    return job;
  }

  @Test
  void aSuccessfulJobIsCompleted() {
    job(1);

    assertThat(service.execute()).isOne();

    assertThat(outcomes).containsExactly("complete");
    assertThat(metrics).containsExactly("done");
  }

  @Test
  void anUnavailableModelSendsTheJobBackWithAnExponentialWait() {
    failures.put(job(1).account(), new EmbeddingUnavailableException("no responde", null));
    failures.put(job(3).account(), new EmbeddingUnavailableException("no responde", null));

    service.execute();

    assertThat(outcomes).containsExactly("retry@5s", "retry@20s");
    assertThat(metrics).containsExactly("unavailable", "unavailable");
  }

  @Test
  void aRejectedJobGoesToReviewWithoutSpendingMoreAttempts() {
    failures.put(job(1).account(), new EmbeddingRejectedException("fuera de contexto"));

    service.execute();

    assertThat(outcomes).containsExactly("fail");
    assertThat(metrics).containsExactly("rejected");
  }

  @Test
  void anUnexpectedErrorIsTreatedAsTransientSoTheJobIsNotLost() {
    failures.put(job(1).account(), new IllegalStateException("algo raro"));

    service.execute();

    assertThat(outcomes).containsExactly("retry@5s");
    assertThat(metrics).containsExactly("error");
  }

  @Test
  void oneFailingJobDoesNotStopTheRestOfThePass() {
    failures.put(job(1).account(), new EmbeddingUnavailableException("no responde", null));
    job(1);
    job(1);

    assertThat(service.execute()).isEqualTo(3);

    assertThat(outcomes).containsExactly("retry@5s", "complete", "complete");
  }

  // La frescura se mide desde que había una edición sin atender hasta que el cálculo termina.
  @Test
  void aFinishedJobRecordsHowLongItsEditWaited() {
    job(1, NOW.minusSeconds(95));
    job(1, NOW.minusSeconds(3));

    service.execute();

    assertThat(freshness).containsExactly(Duration.ofSeconds(95), Duration.ofSeconds(3));
  }

  @Test
  void aJobThatFailsOrIsRetriedRecordsNoFreshness() {
    failures.put(job(1).account(), new EmbeddingUnavailableException("no responde", null));
    failures.put(job(1).account(), new EmbeddingRejectedException("fuera de contexto"));
    failures.put(job(1).account(), new IllegalStateException("raro"));

    service.execute();

    assertThat(freshness).isEmpty();
  }

  @Test
  void anEmptyQueueDoesNothing() {
    assertThat(service.execute()).isZero();
    assertThat(outcomes).isEmpty();
  }

  @Test
  void theBackoffDoublesAndIsCappedAndThePolicyRejectsNonsense() {
    assertThat(policy.backoff(1)).isEqualTo(Duration.ofSeconds(5));
    assertThat(policy.backoff(2)).isEqualTo(Duration.ofSeconds(10));
    assertThat(policy.backoff(40)).isEqualTo(Duration.ofMinutes(5));
    assertThatThrownBy(
            () ->
                new EmbeddingQueuePolicy(
                    0, Duration.ofMinutes(1), 1, Duration.ofSeconds(1), Duration.ofSeconds(2)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new EmbeddingQueuePolicy(
                    1, Duration.ofMinutes(1), 1, Duration.ofSeconds(5), Duration.ofSeconds(2)))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
