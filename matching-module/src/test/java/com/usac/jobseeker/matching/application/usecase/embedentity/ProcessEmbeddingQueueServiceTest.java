package com.usac.jobseeker.matching.application.usecase.embedentity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.usac.jobseeker.common.domain.model.AccountId;
import com.usac.jobseeker.matching.domain.model.JobPostingId;
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
  private final Map<JobPostingId, RuntimeException> jobFailures = new HashMap<>();
  private final List<JobPostingId> embeddedJobs = new ArrayList<>();
  private final EmbeddingQueuePolicy policy =
      new EmbeddingQueuePolicy(
          10, Duration.ofMinutes(5), 5, Duration.ofSeconds(5), Duration.ofMinutes(5));

  private final ProcessEmbeddingQueueService service =
      new ProcessEmbeddingQueueService(
          new EmbeddingWorkQueuePort() {
            public void request(EmbeddingEntity entity, UUID key, Instant notBefore) {
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

            public void discard(EmbeddingEntity entity, UUID key) {
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
          command -> {
            embeddedJobs.add(command.jobId());
            RuntimeException failure = jobFailures.get(command.jobId());
            if (failure != null) {
              throw failure;
            }
            return EmbedJobUseCase.Result.EMBEDDED;
          },
          policy,
          (useCase, outcome) -> metrics.add(outcome),
          freshness::add,
          Clock.fixed(NOW, ZoneOffset.UTC));

  private EmbeddingWorkQueuePort.Job job(int attempts) {
    return job(attempts, NOW.minusSeconds(42));
  }

  private EmbeddingWorkQueuePort.Job job(int attempts, Instant pendingSince) {
    return queued(EmbeddingEntity.PROFILE, attempts, pendingSince);
  }

  private EmbeddingWorkQueuePort.Job queued(
      EmbeddingEntity entity, int attempts, Instant pendingSince) {
    var job =
        new EmbeddingWorkQueuePort.Job(
            UUID.randomUUID(), entity, UUID.randomUUID(), 1, attempts, pendingSince);
    ready.add(job);
    return job;
  }

  private static AccountId accountOf(EmbeddingWorkQueuePort.Job job) {
    return new AccountId(job.key());
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
    failures.put(accountOf(job(1)), new EmbeddingUnavailableException("no responde", null));
    failures.put(accountOf(job(3)), new EmbeddingUnavailableException("no responde", null));

    service.execute();

    assertThat(outcomes).containsExactly("retry@5s", "retry@20s");
    assertThat(metrics).containsExactly("unavailable", "unavailable");
  }

  @Test
  void aRejectedJobGoesToReviewWithoutSpendingMoreAttempts() {
    failures.put(accountOf(job(1)), new EmbeddingRejectedException("fuera de contexto"));

    service.execute();

    assertThat(outcomes).containsExactly("fail");
    assertThat(metrics).containsExactly("rejected");
  }

  @Test
  void anUnexpectedErrorIsTreatedAsTransientSoTheJobIsNotLost() {
    failures.put(accountOf(job(1)), new IllegalStateException("algo raro"));

    service.execute();

    assertThat(outcomes).containsExactly("retry@5s");
    assertThat(metrics).containsExactly("error");
  }

  @Test
  void oneFailingJobDoesNotStopTheRestOfThePass() {
    failures.put(accountOf(job(1)), new EmbeddingUnavailableException("no responde", null));
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
    failures.put(accountOf(job(1)), new EmbeddingUnavailableException("no responde", null));
    failures.put(accountOf(job(1)), new EmbeddingRejectedException("fuera de contexto"));
    failures.put(accountOf(job(1)), new IllegalStateException("raro"));

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
    Duration minute = Duration.ofMinutes(1);
    Duration one = Duration.ofSeconds(1);
    Duration two = Duration.ofSeconds(2);
    Duration five = Duration.ofSeconds(5);
    assertThatThrownBy(() -> new EmbeddingQueuePolicy(0, minute, 1, one, two))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new EmbeddingQueuePolicy(1, minute, 1, five, two))
        .isInstanceOf(IllegalArgumentException.class);
  }

  // ---- vacantes: comparten cola, trabajador y reintentos con los perfiles ----

  @Test
  void aJobEntityIsDispatchedToTheJobUseCaseAndNotToTheProfileOne() {
    var job = queued(EmbeddingEntity.JOB, 1, NOW.minusSeconds(10));

    assertThat(service.execute()).isOne();

    assertThat(embeddedJobs).containsExactly(new JobPostingId(job.key()));
    assertThat(outcomes).containsExactly("complete");
    assertThat(metrics).containsExactly("done");
  }

  @Test
  void aProfileJobNeverReachesTheJobUseCase() {
    job(1);

    service.execute();

    assertThat(embeddedJobs).isEmpty();
  }

  @Test
  void profilesAndJobsInTheSameBatchAreEachDispatchedByTheirKind() {
    job(1);
    var posting = queued(EmbeddingEntity.JOB, 1, NOW.minusSeconds(5));
    job(1);

    assertThat(service.execute()).isEqualTo(3);

    assertThat(embeddedJobs).containsExactly(new JobPostingId(posting.key()));
    assertThat(outcomes).containsExactly("complete", "complete", "complete");
  }

  // El mismo tratamiento de fallos: el modelo caído reintenta con retroceso y un rechazo no.
  @Test
  void aJobFollowsTheSameFailureRulesAsAProfile() {
    var unavailable = queued(EmbeddingEntity.JOB, 1, NOW);
    var rejected = queued(EmbeddingEntity.JOB, 1, NOW);
    jobFailures.put(
        new JobPostingId(unavailable.key()),
        new EmbeddingUnavailableException("no responde", null));
    jobFailures.put(
        new JobPostingId(rejected.key()), new EmbeddingRejectedException("fuera de contexto"));

    service.execute();

    assertThat(outcomes).containsExactly("retry@5s", "fail");
    assertThat(metrics).containsExactly("unavailable", "rejected");
    assertThat(freshness).isEmpty();
  }

  @Test
  void aFinishedJobRecordsHowLongItsEditWaitedLikeAProfile() {
    queued(EmbeddingEntity.JOB, 1, NOW.minusSeconds(61));

    service.execute();

    assertThat(freshness).containsExactly(Duration.ofSeconds(61));
  }
}
