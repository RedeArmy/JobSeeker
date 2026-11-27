package com.usac.jobseeker.ats.infrastructure.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.usac.jobseeker.ats.domain.model.Application;
import com.usac.jobseeker.ats.domain.model.ApplicationId;
import com.usac.jobseeker.ats.infrastructure.AtsPostgres;
import com.usac.jobseeker.ats.infrastructure.AtsPostgres.Parties;
import com.usac.jobseeker.common.domain.model.AccountId;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Las postulaciones contra Postgres real: el índice de BR-TRACK-01 y 05, el historial y la carrera.
 */
class JdbcApplicationRepositoryTest {

  private static final Instant NOW = Instant.now().truncatedTo(ChronoUnit.MICROS);

  private final JdbcClient jdbc = JdbcClient.create(AtsPostgres.dataSource());
  private final JdbcApplicationRepository store = new JdbcApplicationRepository(jdbc);
  private final Parties parties = AtsPostgres.parties(jdbc);
  private final ExecutorService pool = Executors.newFixedThreadPool(2);

  @AfterEach
  void stop() {
    pool.shutdownNow();
  }

  private Application.Transition submission(UUID job) {
    return Application.submit(
        new ApplicationId(UUID.randomUUID()),
        parties.profile(),
        new AccountId(parties.account()),
        job,
        parties.cvFile(),
        UUID.randomUUID(),
        NOW);
  }

  private boolean insert(Application.Transition transition) {
    return store.insertIfNoneOpen(transition.application(), transition.change());
  }

  private void setStatus(ApplicationId id, String status, String reason) {
    jdbc.sql("update applications set status = :status, rejection_reason = :reason where id = :id")
        .param("status", status)
        .param("reason", reason)
        .param("id", id.value())
        .update();
  }

  private int historyOf(ApplicationId id) {
    return jdbc.sql("select count(*) from application_status_history where application_id = :id")
        .param("id", id.value())
        .query(Integer.class)
        .single();
  }

  @Test
  void anApplicationIsStoredWithItsFirstHistoryRowAndReadBackEqual() {
    Application.Transition submitted = submission(parties.job());

    assertThat(insert(submitted)).isTrue();

    assertThat(store.findById(submitted.application().id())).contains(submitted.application());
    assertThat(historyOf(submitted.application().id())).isEqualTo(1);
    assertThat(
            jdbc.sql(
                    "select from_status is null and to_status = 'ACTIVE' and to_stage = 'NEW'"
                        + " and actor = 'CANDIDATE' from application_status_history"
                        + " where application_id = :id")
                .param("id", submitted.application().id().value())
                .query(Boolean.class)
                .single())
        .isTrue();
    assertThat(store.hasOpenApplication(parties.profile(), parties.job())).isTrue();
  }

  @Test
  void aSecondApplicationToTheSamePostingWritesNothing() {
    insert(submission(parties.job()));
    Application.Transition again = submission(parties.job());

    assertThat(insert(again)).isFalse();

    assertThat(store.findById(again.application().id())).isEmpty();
    assertThat(historyOf(again.application().id())).isZero();
  }

  // REQ-C-03: quien se retiró puede volver a postular, y la retirada se conserva.
  @Test
  void afterWithdrawingTheCandidateCanApplyAgain() {
    Application.Transition first = submission(parties.job());
    insert(first);
    setStatus(first.application().id(), "WITHDRAWN", null);

    assertThat(store.hasOpenApplication(parties.profile(), parties.job())).isFalse();
    assertThat(insert(submission(parties.job()))).isTrue();
    assertThat(store.findById(first.application().id())).isPresent();
  }

  // BR-TRACK-05 y D3: una descartada o contratada sigue contando; solo el reclutador la reabre.
  @Test
  void aRejectedOrHiredApplicationStillBlocksANewOne() {
    Application.Transition rejected = submission(parties.job());
    insert(rejected);
    setStatus(rejected.application().id(), "REJECTED", "NOT_A_FIT");

    assertThat(store.hasOpenApplication(parties.profile(), parties.job())).isTrue();
    assertThat(insert(submission(parties.job()))).isFalse();

    UUID otherJob = AtsPostgres.job(jdbc, AtsPostgres.account(jdbc, false, true));
    Application.Transition hired = submission(otherJob);
    insert(hired);
    setStatus(hired.application().id(), "HIRED", null);
    assertThat(insert(submission(otherJob))).isFalse();
  }

  @Test
  void anApplicationToOnePostingNeverBlocksAnother() {
    insert(submission(parties.job()));
    UUID sameRecruiterOtherJob =
        AtsPostgres.job(
            jdbc,
            jdbc.sql("select recruiter_account_id from job_postings where id = :id")
                .param("id", parties.job())
                .query(UUID.class)
                .single());

    assertThat(insert(submission(sameRecruiterOtherJob))).isTrue();
  }

  // La base es la última defensa: un descarte sin motivo o un motivo fuera del catálogo no entran.
  @Test
  void theDatabaseRejectsAnIncoherentRejection() {
    Application.Transition submitted = submission(parties.job());
    insert(submitted);
    ApplicationId id = submitted.application().id();

    assertThatThrownBy(() -> setStatus(id, "REJECTED", null))
        .isInstanceOf(DataIntegrityViolationException.class);
    assertThatThrownBy(() -> setStatus(id, "REJECTED", "BECAUSE"))
        .isInstanceOf(DataIntegrityViolationException.class);
    assertThatThrownBy(() -> setStatus(id, "ACTIVE", "NOT_A_FIT"))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void aCvVersionThatDoesNotExistCannotBeReferenced() {
    Application.Transition submitted =
        Application.submit(
            new ApplicationId(UUID.randomUUID()),
            parties.profile(),
            new AccountId(parties.account()),
            parties.job(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            NOW);

    assertThatThrownBy(() -> insert(submitted)).isInstanceOf(DataIntegrityViolationException.class);
  }

  // Regla 4 del TDD: dos envíos simultáneos, cada uno en su transacción, tienen exactamente un
  // ganador, ronda tras ronda (como la carrera de publicar en `job`).
  @Test
  void twoSimultaneousSubmissionsHaveExactlyOneWinner() throws Exception {
    TransactionTemplate transactions =
        new TransactionTemplate(new DataSourceTransactionManager(AtsPostgres.dataSource()));
    for (int round = 0; round < 10; round++) {
      Parties fresh = AtsPostgres.parties(jdbc);
      CyclicBarrier start = new CyclicBarrier(2);
      List<Future<Boolean>> results = new ArrayList<>();
      for (int i = 0; i < 2; i++) {
        results.add(
            pool.submit(
                () ->
                    transactions.execute(
                        status -> {
                          await(start);
                          Application.Transition t =
                              Application.submit(
                                  new ApplicationId(UUID.randomUUID()),
                                  fresh.profile(),
                                  new AccountId(fresh.account()),
                                  fresh.job(),
                                  fresh.cvFile(),
                                  UUID.randomUUID(),
                                  NOW);
                          return store.insertIfNoneOpen(t.application(), t.change());
                        })));
      }
      int winners = 0;
      for (Future<Boolean> result : results) {
        if (Boolean.TRUE.equals(result.get(30, TimeUnit.SECONDS))) {
          winners++;
        }
      }
      assertThat(winners).as("ronda %d", round).isEqualTo(1);
      assertThat(
              jdbc.sql("select count(*) from applications where job_posting_id = :job")
                  .param("job", fresh.job())
                  .query(Integer.class)
                  .single())
          .isEqualTo(1);
    }
  }

  private static void await(CyclicBarrier barrier) {
    try {
      barrier.await(10, TimeUnit.SECONDS);
    } catch (Exception failure) {
      throw new IllegalStateException(failure);
    }
  }
}
