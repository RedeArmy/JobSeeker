package com.usac.jobseeker.matching.infrastructure.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.usac.jobseeker.common.domain.model.AccountId;
import com.usac.jobseeker.matching.domain.model.CandidateProfileId;
import com.usac.jobseeker.matching.domain.model.JobPostingId;
import com.usac.jobseeker.matching.infrastructure.MatchingPostgres;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * El registro de perfiles vistos contra Postgres real: ventana, únicos y el candado por reclutador.
 */
class JdbcProfileViewLogTest {

  private final JdbcClient jdbc = JdbcClient.create(MatchingPostgres.dataSource());
  private final JdbcProfileViewLog log = new JdbcProfileViewLog(jdbc);
  private final AccountId viewer = new AccountId(UUID.randomUUID());
  private final JobPostingId job = new JobPostingId(UUID.randomUUID());
  private final Instant now = Instant.parse("2026-11-25T12:00:00Z");

  @BeforeEach
  void clean() {
    jdbc.sql("delete from profile_view_log").update();
  }

  private static CandidateProfileId candidate() {
    return new CandidateProfileId(UUID.randomUUID());
  }

  @Test
  void whatWasRecordedIsViewedWithinTheWindowAndOnlyThen() {
    var recent = candidate();
    var old = candidate();
    log.record(viewer, job, List.of(old), now.minusSeconds(25 * 3600));
    log.record(viewer, job, List.of(recent), now.minusSeconds(3600));

    assertThat(log.viewedSince(viewer, now.minusSeconds(24 * 3600))).containsExactly(recent);
  }

  // La cuota cuenta perfiles distintos: verlo dos veces es uno.
  @Test
  void aProfileSeenTwiceCountsOnce() {
    var same = candidate();
    log.record(viewer, job, List.of(same), now.minusSeconds(60));
    log.record(viewer, job, List.of(same), now.minusSeconds(30));

    assertThat(log.viewedSince(viewer, now.minusSeconds(3600))).containsExactly(same);
  }

  @Test
  void whatAnotherRecruiterSawIsNotMine() {
    log.record(new AccountId(UUID.randomUUID()), job, List.of(candidate()), now);

    assertThat(log.viewedSince(viewer, now.minusSeconds(3600))).isEmpty();
  }

  @Test
  void theRowHoldsOnlyOpaqueIdentifiersAndTheContext() {
    var seen = candidate();
    log.record(viewer, job, List.of(seen), now);

    var row =
        jdbc.sql(
                "select viewer_account_id, candidate_profile_id, job_posting_id, context"
                    + " from profile_view_log")
            .query()
            .singleRow();

    assertThat(row)
        .containsEntry("viewer_account_id", viewer.value())
        .containsEntry("candidate_profile_id", seen.value())
        .containsEntry("job_posting_id", job.value())
        .containsEntry("context", "SUGGESTION");
  }

  // Sin el candado, dos pedidos simultáneos leerían ambos «0 vistos» y los dos pasarían la cuota.
  @Test
  void theLockPutsConcurrentRequestsOfTheSameViewerInLine() throws Exception {
    var transactions =
        new TransactionTemplate(new DataSourceTransactionManager(MatchingPostgres.dataSource()));
    int quota = 3;
    int callers = 6;
    ExecutorService pool = Executors.newFixedThreadPool(callers);
    CountDownLatch start = new CountDownLatch(1);
    try {
      List<Future<Integer>> futures =
          java.util.stream.IntStream.range(0, callers)
              .mapToObj(
                  i ->
                      pool.submit(
                          () -> {
                            start.await();
                            return transactions.execute(
                                tx -> {
                                  log.lock(viewer);
                                  int seen = log.viewedSince(viewer, now.minusSeconds(3600)).size();
                                  if (seen >= quota) {
                                    return 0;
                                  }
                                  log.record(viewer, job, List.of(candidate()), now);
                                  return 1;
                                });
                          }))
              .toList();
      start.countDown();
      int admitted = 0;
      for (Future<Integer> future : futures) {
        admitted += future.get(30, TimeUnit.SECONDS);
      }

      assertThat(admitted).isEqualTo(quota);
    } finally {
      pool.shutdownNow();
    }
  }
}
