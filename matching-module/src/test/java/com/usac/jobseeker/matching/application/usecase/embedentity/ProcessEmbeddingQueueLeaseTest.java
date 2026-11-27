package com.usac.jobseeker.matching.application.usecase.embedentity;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * El lote de la cola se arrienda entero y se ejecuta en serie: lo que no alcanza a empezar dentro
 * del margen del arrendamiento se devuelve, para que otro trabajador no lo tome mientras este lo
 * tiene.
 */
class ProcessEmbeddingQueueLeaseTest {

  private static final Instant NOW = Instant.parse("2026-11-27T10:00:00Z");
  private static final Duration LEASE = Duration.ofMinutes(5);

  private final List<String> outcomes = new ArrayList<>();
  private final List<String> metrics = new ArrayList<>();
  private final MovingClock clock = new MovingClock();
  private final List<EmbeddingWorkQueuePort.Job> ready = List.of(job(), job(), job());

  private static EmbeddingWorkQueuePort.Job job() {
    return new EmbeddingWorkQueuePort.Job(
        UUID.randomUUID(), EmbeddingEntity.PROFILE, UUID.randomUUID(), 1, 1, NOW);
  }

  @Test
  void jobsThatCannotStartWithinTheSafeWindowAreReleasedNotRun() {
    var service =
        new ProcessEmbeddingQueueService(
            new EmbeddingWorkQueuePort() {
              @Override
              public void request(EmbeddingEntity entity, UUID key, Instant notBefore) {
                throw new UnsupportedOperationException();
              }

              @Override
              public List<Job> claim(int limit, Duration lease, int maxAttempts) {
                return ready;
              }

              @Override
              public void complete(Job job) {
                outcomes.add("complete");
              }

              @Override
              public void retryAt(Job job, Instant notBefore) {
                outcomes.add("retry");
              }

              @Override
              public void fail(Job job) {
                outcomes.add("fail");
              }

              @Override
              public void release(Job job) {
                outcomes.add("release");
              }

              @Override
              public void discard(EmbeddingEntity entity, UUID key) {
                throw new UnsupportedOperationException();
              }
            },
            new EmbeddingJobHandlers(
                command -> {
                  // El primer cálculo tarda todo el margen del lote.
                  clock.now = NOW.plus(LEASE.multipliedBy(4).dividedBy(5));
                  return EmbedProfileUseCase.Result.EMBEDDED;
                },
                command -> EmbedJobUseCase.Result.EMBEDDED,
                command -> 0),
            new EmbeddingQueuePolicy(10, LEASE, 5, Duration.ofSeconds(5), Duration.ofMinutes(5)),
            (useCase, outcome) -> metrics.add(outcome),
            elapsed -> {},
            clock);

    assertThat(service.execute()).isEqualTo(3);

    assertThat(outcomes).containsExactly("complete", "release", "release");
    assertThat(metrics).containsExactly("done", "released", "released");
  }

  // El trabajo que un doble de la cola no sabe devolver se reprograma para ya (método por defecto).
  @Test
  void theDefaultReleaseReschedulesTheJobForNow() {
    List<Instant> retried = new ArrayList<>();
    EmbeddingWorkQueuePort port =
        new EmbeddingWorkQueuePort() {
          @Override
          public void request(EmbeddingEntity entity, UUID key, Instant notBefore) {
            throw new UnsupportedOperationException("la prueba no pide trabajos");
          }

          @Override
          public List<Job> claim(int limit, Duration lease, int maxAttempts) {
            return List.of();
          }

          @Override
          public void complete(Job job) {
            throw new UnsupportedOperationException("la prueba no completa trabajos");
          }

          @Override
          public void retryAt(Job job, Instant notBefore) {
            retried.add(notBefore);
          }

          @Override
          public void fail(Job job) {
            throw new UnsupportedOperationException("la prueba no da trabajos por fallidos");
          }

          @Override
          public void discard(EmbeddingEntity entity, UUID key) {
            throw new UnsupportedOperationException("la prueba no descarta trabajos");
          }
        };

    port.release(job());

    assertThat(retried).containsExactly(Instant.EPOCH);
  }

  private static final class MovingClock extends Clock {
    Instant now = NOW;

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return now;
    }
  }
}
