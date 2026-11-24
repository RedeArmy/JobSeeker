package com.usac.jobseeker.matching.infrastructure.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.usac.jobseeker.common.domain.model.AccountId;
import com.usac.jobseeker.matching.infrastructure.MatchingPostgres;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * La cola de embeddings contra Postgres real: una fila por cuenta, agrupación de peticiones,
 * arrendamiento, reintentos, la petición que llega mientras el trabajo corre y la concurrencia.
 */
class JdbcEmbeddingWorkQueueTest {

  private static final Duration LEASE = Duration.ofMinutes(5);

  private final JdbcClient jdbc = JdbcClient.create(MatchingPostgres.dataSource());
  private final JdbcEmbeddingWorkQueue queue = new JdbcEmbeddingWorkQueue(jdbc);
  private final AccountId account = new AccountId(UUID.randomUUID());

  @BeforeEach
  void clean() {
    jdbc.sql("delete from matching_work_queue").update();
  }

  private int rows() {
    return jdbc.sql("select count(*) from matching_work_queue").query(Integer.class).single();
  }

  private String status(AccountId a) {
    return jdbc.sql("select status from matching_work_queue where entity_key = :k")
        .param("k", a.value())
        .query(String.class)
        .single();
  }

  private void expireLease(AccountId a) {
    jdbc.sql(
            "update matching_work_queue set locked_until = now() - interval '1 second' where entity_key = :k")
        .param("k", a.value())
        .update();
  }

  @Test
  void aRequestBecomesOneJobThatCanBeClaimedOnceItsWindowPasses() {
    queue.request(account, Instant.now().minusSeconds(1));

    var claimed = queue.claim(10, LEASE, 5);

    assertThat(claimed)
        .singleElement()
        .satisfies(
            job -> {
              assertThat(job.account()).isEqualTo(account);
              assertThat(job.attempts()).isOne();
              assertThat(job.generation()).isOne();
            });
  }

  @Test
  void aJobInsideItsWindowIsNotClaimedYet() {
    queue.request(account, Instant.now().plusSeconds(3600));

    assertThat(queue.claim(10, LEASE, 5)).isEmpty();
  }

  @Test
  void manyRequestsForTheSameAccountAreOneJobAndKeepTheEarliestStart() {
    queue.request(account, Instant.now().plusSeconds(3600));
    queue.request(account, Instant.now().minusSeconds(1));
    queue.request(account, Instant.now().plusSeconds(7200));

    assertThat(rows()).isOne();
    assertThat(queue.claim(10, LEASE, 5))
        .singleElement()
        .satisfies(job -> assertThat(job.generation()).isEqualTo(3));
  }

  @Test
  void aClaimedJobIsLeasedSoNobodyElseClaimsIt() {
    queue.request(account, Instant.now().minusSeconds(1));
    queue.claim(10, LEASE, 5);

    assertThat(queue.claim(10, LEASE, 5)).isEmpty();
  }

  @Test
  void whenTheLeaseExpiresTheJobIsClaimedAgainAndCountsTheAttempt() {
    queue.request(account, Instant.now().minusSeconds(1));
    queue.claim(10, LEASE, 5);
    expireLease(account);

    assertThat(queue.claim(10, LEASE, 5))
        .singleElement()
        .satisfies(job -> assertThat(job.attempts()).isEqualTo(2));
  }

  @Test
  void aJobThatKeepsKillingTheProcessEndsFailedInsteadOfBeingClaimedForever() {
    queue.request(account, Instant.now().minusSeconds(1));
    for (int crash = 0; crash < 3; crash++) {
      queue.claim(10, LEASE, 3);
      expireLease(account);
    }

    assertThat(queue.claim(10, LEASE, 3)).isEmpty();
    assertThat(status(account)).isEqualTo("FAILED");
  }

  @Test
  void completingAJobNobodyRequestedAgainDeletesIt() {
    queue.request(account, Instant.now().minusSeconds(1));
    var job = queue.claim(10, LEASE, 5).get(0);

    queue.complete(job);

    assertThat(rows()).isZero();
  }

  // La petición que llega mientras el trabajo corre no se pierde: se libera para volver a
  // ejecutarse.
  @Test
  void aRequestThatArrivesWhileTheJobRunsIsNotLost() {
    queue.request(account, Instant.now().minusSeconds(1));
    var job = queue.claim(10, LEASE, 5).get(0);
    queue.request(account, Instant.now().minusSeconds(1));

    queue.complete(job);

    assertThat(rows()).as("sigue ahí").isOne();
    assertThat(queue.claim(10, LEASE, 5))
        .singleElement()
        .satisfies(
            again -> {
              assertThat(again.generation()).isEqualTo(2);
              assertThat(again.attempts()).as("el intento exitoso reinicia la cuenta").isOne();
            });
  }

  @Test
  void aRetryReleasesTheLeaseAndWaitsForItsTime() {
    queue.request(account, Instant.now().minusSeconds(1));
    var job = queue.claim(10, LEASE, 5).get(0);

    queue.retryAt(job, Instant.now().plusSeconds(3600));
    assertThat(queue.claim(10, LEASE, 5)).isEmpty();

    queue.retryAt(job, Instant.now().minusSeconds(1));
    assertThat(queue.claim(10, LEASE, 5)).hasSize(1);
  }

  @Test
  void aFailedJobIsNeverClaimedAgainUntilANewRequestReactivatesIt() {
    queue.request(account, Instant.now().minusSeconds(1));
    var job = queue.claim(10, LEASE, 5).get(0);
    queue.fail(job);

    assertThat(queue.claim(10, LEASE, 5)).isEmpty();
    assertThat(status(account)).isEqualTo("FAILED");

    queue.request(account, Instant.now().minusSeconds(1));

    assertThat(queue.claim(10, LEASE, 5))
        .singleElement()
        .satisfies(again -> assertThat(again.attempts()).isOne());
  }

  // La frescura se mide desde la edición sin atender más antigua, no desde la última.
  @Test
  void theJobKeepsTheEarliestUnattendedEditWhileRequestsAreCoalesced() {
    queue.request(account, Instant.now().minusSeconds(1));
    jdbc.sql("update matching_work_queue set pending_since = now() - interval '90 seconds'")
        .update();

    queue.request(account, Instant.now().minusSeconds(1));
    var job = queue.claim(10, LEASE, 5).get(0);

    assertThat(Duration.between(job.pendingSince(), Instant.now()).toSeconds())
        .isBetween(89L, 120L);
  }

  @Test
  void aRequestThatArrivesWhileTheJobRunsRestartsTheClockForTheNextRun() {
    queue.request(account, Instant.now().minusSeconds(1));
    jdbc.sql("update matching_work_queue set pending_since = now() - interval '90 seconds'")
        .update();
    var job = queue.claim(10, LEASE, 5).get(0);
    queue.request(account, Instant.now().minusSeconds(1));

    queue.complete(job);
    var again = queue.claim(10, LEASE, 5).get(0);

    assertThat(Duration.between(again.pendingSince(), Instant.now()).toSeconds()).isLessThan(30L);
  }

  @Test
  void aFailedJobReactivatedByAnEditStartsMeasuringFromThatEdit() {
    queue.request(account, Instant.now().minusSeconds(1));
    var job = queue.claim(10, LEASE, 5).get(0);
    queue.fail(job);
    jdbc.sql("update matching_work_queue set pending_since = now() - interval '1 day'").update();

    queue.request(account, Instant.now().minusSeconds(1));
    var again = queue.claim(10, LEASE, 5).get(0);

    assertThat(Duration.between(again.pendingSince(), Instant.now()).toSeconds()).isLessThan(30L);
  }

  @Test
  void discardingAnAccountRemovesItsPendingJob() {
    queue.request(account, Instant.now().minusSeconds(1));
    queue.request(new AccountId(UUID.randomUUID()), Instant.now().minusSeconds(1));

    queue.discard(account);

    assertThat(rows()).isOne();
  }

  @Test
  void theBatchSizeBoundsHowManyJobsAreClaimed() {
    for (int i = 0; i < 12; i++) {
      queue.request(new AccountId(UUID.randomUUID()), Instant.now().minusSeconds(1));
    }

    assertThat(queue.claim(5, LEASE, 5)).hasSize(5);
  }

  @Test
  void fourWorkersClaimingAtTheSameTimeNeverTakeTheSameJob() throws Exception {
    for (int i = 0; i < 40; i++) {
      queue.request(new AccountId(UUID.randomUUID()), Instant.now().minusSeconds(1));
    }
    List<UUID> claimed = new CopyOnWriteArrayList<>();
    CountDownLatch together = new CountDownLatch(1);
    ExecutorService pool = Executors.newFixedThreadPool(4);
    try {
      List<Future<?>> runs = new ArrayList<>();
      for (int i = 0; i < 4; i++) {
        runs.add(
            pool.submit(
                () -> {
                  together.await();
                  for (int pass = 0; pass < 6; pass++) {
                    queue.claim(10, LEASE, 5).forEach(job -> claimed.add(job.id()));
                  }
                  return null;
                }));
      }
      together.countDown();
      for (Future<?> run : runs) {
        run.get(60, TimeUnit.SECONDS);
      }
    } finally {
      pool.shutdownNow();
    }

    assertThat(claimed).hasSize(40).doesNotHaveDuplicates();
  }
}
