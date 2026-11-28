package com.usac.jobseeker.matching.infrastructure.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.usac.jobseeker.matching.application.usecase.calculatecompatibilityscore.PairFixtures;
import com.usac.jobseeker.matching.domain.model.ApplicationProfileSnapshot;
import com.usac.jobseeker.matching.domain.model.CandidateProfileId;
import com.usac.jobseeker.matching.domain.model.CandidateSignals;
import com.usac.jobseeker.matching.domain.model.JobPostingId;
import com.usac.jobseeker.matching.domain.model.JobSignals;
import com.usac.jobseeker.matching.domain.model.ScoreReason;
import com.usac.jobseeker.matching.domain.model.ScoreSnapshot;
import com.usac.jobseeker.matching.domain.model.SnapshotReason;
import com.usac.jobseeker.matching.domain.model.StoredScore;
import com.usac.jobseeker.matching.infrastructure.MatchingPostgres;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * El almacén del puntaje contra las tablas reales (Postgres 17): ida y vuelta sin pérdida, el
 * *upsert* en una sola sentencia, el motivo más fuerte que nunca baja, el orden de lectura, las
 * fotos con su retención y la anonimización que vacía lo derivado y conserva el metadato.
 */
class JdbcScoreStoreTest {

  private static final Instant T0 = Instant.parse("2026-11-26T10:00:00Z");

  private final JdbcClient jdbc = JdbcClient.create(MatchingPostgres.dataSource());
  private final JdbcScoreStore store = new JdbcScoreStore(jdbc);
  private final PairFixtures world = new PairFixtures();

  @BeforeEach
  void clean() {
    jdbc.sql("delete from compatibility_scores_current").update();
    jdbc.sql("delete from compatibility_score_snapshots").update();
    jdbc.sql("delete from application_profile_snapshots").update();
  }

  private StoredScore stored(
      CandidateSignals candidate, JobSignals job, double cosine, ScoreReason reason, Instant at) {
    return new StoredScore(
        candidate.profileId(),
        job.jobId(),
        world.calculator.score(candidate, job, cosine),
        reason,
        at);
  }

  private StoredScore aPair(ScoreReason reason) {
    return stored(world.candidate("Java"), world.job("PUBLISHED"), 0.7, reason, T0);
  }

  private String reasonOf(StoredScore pair) {
    return jdbc.sql(
            "select reason from compatibility_scores_current where candidate_profile_id = :p and job_posting_id = :j")
        .param("p", pair.profile().value())
        .param("j", pair.job().value())
        .query(String.class)
        .single();
  }

  // ---- ida y vuelta ----

  @Test
  void aScoreIsReadBackExactlyAsItWasStored() {
    var pair = aPair(ScoreReason.SUGGESTED);

    store.upsertCurrent(List.of(pair));

    var read = store.findCurrent(pair.profile(), pair.job()).orElseThrow();
    assertThat(read.reason()).isEqualTo(ScoreReason.SUGGESTED);
    assertThat(read.computedAt()).isEqualTo(T0);
    assertThat(read.score().breakdown().dimensions())
        .isEqualTo(pair.score().breakdown().dimensions());
    assertThat(read.score().overall())
        .isCloseTo(pair.score().overall(), org.assertj.core.data.Offset.offset(0.01));
    assertThat(read.score().breakdown().completeness())
        .isCloseTo(
            pair.score().breakdown().completeness(), org.assertj.core.data.Offset.offset(0.001));
    assertThat(read.score().calibrated()).isFalse();
    assertThat(read.score().version().value()).isEqualTo(pair.score().version().value());
  }

  @Test
  void aMissingPairIsAbsent() {
    assertThat(
            store.findCurrent(
                new CandidateProfileId(UUID.randomUUID()), new JobPostingId(UUID.randomUUID())))
        .isEmpty();
  }

  // ---- upsert ----

  @Test
  void manyPairsAreStoredWithOneStatementAndAnExistingOneIsReplaced() {
    var first = aPair(ScoreReason.SUGGESTED);
    var second = aPair(ScoreReason.SUGGESTED);
    store.upsertCurrent(List.of(first));
    var replacement =
        new StoredScore(
            first.profile(),
            first.job(),
            second.score(),
            ScoreReason.SUGGESTED,
            T0.plusSeconds(60));

    store.upsertCurrent(List.of(replacement, second));

    assertThat(count()).isEqualTo(2);
    assertThat(store.findCurrent(first.profile(), first.job()).orElseThrow().computedAt())
        .isEqualTo(T0.plusSeconds(60));
  }

  // Un par repetido en la misma sentencia haría fallar el ON CONFLICT: se queda con el último.
  @Test
  void aRepeatedPairInTheSameBatchKeepsTheLastOne() {
    var first = aPair(ScoreReason.SUGGESTED);
    var later =
        new StoredScore(
            first.profile(), first.job(), first.score(), ScoreReason.SUGGESTED, T0.plusSeconds(5));

    store.upsertCurrent(List.of(first, later));

    assertThat(count()).isEqualTo(1);
    assertThat(store.findCurrent(first.profile(), first.job()).orElseThrow().computedAt())
        .isEqualTo(T0.plusSeconds(5));
  }

  @Test
  void anEmptyBatchStoresNothing() {
    store.upsertCurrent(List.of());

    assertThat(count()).isZero();
  }

  // El motivo más fuerte se conserva: un postulado nunca baja a sugerido.
  @Test
  void theStrongestReasonIsKeptAndAStrongerOneRaisesIt() {
    var pair = aPair(ScoreReason.APPLIED);
    store.upsertCurrent(List.of(pair));

    store.upsertCurrent(List.of(withReason(pair, ScoreReason.SUGGESTED)));
    assertThat(reasonOf(pair)).isEqualTo("APPLIED");
    store.upsertCurrent(List.of(withReason(pair, ScoreReason.CANDIDATE_VIEW)));
    assertThat(reasonOf(pair)).isEqualTo("APPLIED");

    var other = aPair(ScoreReason.SUGGESTED);
    store.upsertCurrent(List.of(other));
    store.upsertCurrent(List.of(withReason(other, ScoreReason.CANDIDATE_VIEW)));
    assertThat(reasonOf(other)).isEqualTo("CANDIDATE_VIEW");
    store.upsertCurrent(List.of(withReason(other, ScoreReason.SUGGESTED)));
    assertThat(reasonOf(other)).isEqualTo("CANDIDATE_VIEW");
    store.upsertCurrent(List.of(withReason(other, ScoreReason.APPLIED)));
    assertThat(reasonOf(other)).isEqualTo("APPLIED");
  }

  // Un recálculo lento que empezó antes no pisa un puntaje más reciente; el motivo sí se combina.
  @Test
  void anOlderCalculationNeverOverwritesANewerScoreButItsReasonStillCounts() {
    var candidate = world.candidate("Java");
    var job = world.job("PUBLISHED");
    var newer = stored(candidate, job, 0.9, ScoreReason.SUGGESTED, T0.plusSeconds(60));
    var older = stored(candidate, job, 0.1, ScoreReason.APPLIED, T0);
    assertThat(older.score().overall()).isNotEqualTo(newer.score().overall());

    store.upsertCurrent(List.of(newer));
    store.upsertCurrent(List.of(older));

    var read = store.findCurrent(newer.profile(), newer.job()).orElseThrow();
    assertThat(read.computedAt()).isEqualTo(T0.plusSeconds(60));
    assertThat(read.score().overall())
        .isCloseTo(newer.score().overall(), org.assertj.core.data.Offset.offset(0.01));
    assertThat(read.reason()).isEqualTo(ScoreReason.APPLIED);
  }

  // Pares cruzados escritos a la vez por dos transacciones, en órdenes opuestos: con las filas
  // ordenadas antes de escribir ninguna espera a la otra en ciclo. Sin el orden, Postgres puede
  // abortar una con «deadlock detected»; la carrera no es determinista, así que se repite.
  @Test
  void crossedBatchesWrittenConcurrentlyDoNotDeadlock() throws Exception {
    var a = world.candidate("Java");
    var b = world.candidate("Kotlin");
    var x = world.job("PUBLISHED");
    var y = world.job("PUBLISHED");
    List<StoredScore> forward =
        List.of(
            stored(a, x, 0.5, ScoreReason.SUGGESTED, T0),
            stored(a, y, 0.5, ScoreReason.SUGGESTED, T0),
            stored(b, x, 0.5, ScoreReason.SUGGESTED, T0),
            stored(b, y, 0.5, ScoreReason.SUGGESTED, T0));
    List<StoredScore> backward = new java.util.ArrayList<>(forward);
    java.util.Collections.reverse(backward);
    store.upsertCurrent(forward);
    var transactions =
        new org.springframework.transaction.support.TransactionTemplate(
            new org.springframework.jdbc.datasource.DataSourceTransactionManager(
                MatchingPostgres.dataSource()));
    var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
    try {
      var futures = new java.util.ArrayList<java.util.concurrent.Future<?>>();
      for (int round = 0; round < 20; round++) {
        futures.add(
            pool.submit(
                () -> transactions.executeWithoutResult(t -> store.upsertCurrent(forward))));
        futures.add(
            pool.submit(
                () -> transactions.executeWithoutResult(t -> store.upsertCurrent(backward))));
      }
      for (var future : futures) {
        future.get(30, java.util.concurrent.TimeUnit.SECONDS);
      }
    } finally {
      pool.shutdownNow();
    }

    assertThat(count()).isEqualTo(4);
  }

  // TD-045: con la tabla poblada y casi todo en la versión vigente, buscar los pares de versiones
  // viejas usa el índice de la versión y no recorre la tabla entera.
  @Test
  void findingStalePairsUsesTheVersionIndexInsteadOfScanningTheTable() {
    jdbc.sql(
            "insert into compatibility_scores_current (candidate_profile_id, job_posting_id,"
                + " overall, completeness, calibrated, algorithm_version, breakdown, reason,"
                + " computed_at) select gen_random_uuid(), gen_random_uuid(), 50, 0.5, false,"
                + " case when g % 1000 = 0 then 'c|m|w|1' else 'c|m|w|2' end, cast('{}' as jsonb),"
                + " 'SUGGESTED', now() - make_interval(secs => g) from generate_series(1, 20000) g")
        .update();
    jdbc.sql("analyze compatibility_scores_current").update();

    String plan =
        String.join(
            "\n",
            jdbc.sql(
                    "explain "
                        + JdbcScoreStore.SELECT_CURRENT
                        + JdbcScoreStore.STALE_VERSION
                        + " order by computed_at limit 100")
                .param("version", "c|m|w|2")
                .query(String.class)
                .list());

    assertThat(plan).contains("idx_csc_version").doesNotContain("Seq Scan");
    assertThat(
            jdbc.sql(
                    "select count(*) from compatibility_scores_current"
                        + JdbcScoreStore.STALE_VERSION)
                .param("version", "c|m|w|2")
                .query(Integer.class)
                .single())
        .isEqualTo(20);
  }

  private static StoredScore withReason(StoredScore pair, ScoreReason reason) {
    return new StoredScore(pair.profile(), pair.job(), pair.score(), reason, pair.computedAt());
  }

  private int count() {
    return jdbc.sql("select count(*) from compatibility_scores_current")
        .query(Integer.class)
        .single();
  }

  // ---- lectura por perfil, por vacante y por versión ----

  @Test
  void thePairsOfAProfileAndOfAJobComeMostRecentFirstAndRespectTheLimit() {
    var candidate = world.candidate("Java");
    var jobs = List.of(world.job("PUBLISHED"), world.job("PUBLISHED"), world.job("PUBLISHED"));
    for (int i = 0; i < jobs.size(); i++) {
      store.upsertCurrent(
          List.of(stored(candidate, jobs.get(i), 0.5, ScoreReason.SUGGESTED, T0.plusSeconds(i))));
    }

    assertThat(store.jobsOfProfile(candidate.profileId(), 10))
        .containsExactly(jobs.get(2).jobId(), jobs.get(1).jobId(), jobs.get(0).jobId());
    assertThat(store.jobsOfProfile(candidate.profileId(), 2)).hasSize(2);

    var job = world.job("PUBLISHED");
    var people = List.of(world.candidate("Java"), world.candidate("Java"));
    for (int i = 0; i < people.size(); i++) {
      store.upsertCurrent(
          List.of(stored(people.get(i), job, 0.5, ScoreReason.SUGGESTED, T0.plusSeconds(i))));
    }
    assertThat(store.profilesOfJob(job.jobId(), 10))
        .containsExactly(people.get(1).profileId(), people.get(0).profileId());
  }

  @Test
  void staleVersionsComeOldestFirstExcludeTheCurrentVersionAndAnonymizedRows() {
    var current = aPair(ScoreReason.SUGGESTED);
    var old = aPair(ScoreReason.SUGGESTED);
    var anonymized = aPair(ScoreReason.SUGGESTED);
    store.upsertCurrent(List.of(current, old, anonymized));
    jdbc.sql(
            "update compatibility_scores_current set algorithm_version = '0|viejo|aaaaaaaa|bbbbbbbb', computed_at = :t where candidate_profile_id = :p")
        .param(
            "t", java.time.OffsetDateTime.ofInstant(T0.minusSeconds(100), java.time.ZoneOffset.UTC))
        .param("p", old.profile().value())
        .update();
    store.anonymizeProfile(anonymized.profile(), T0);
    jdbc.sql(
            "update compatibility_scores_current set algorithm_version = '0|viejo|aaaaaaaa|bbbbbbbb' where candidate_profile_id = :p")
        .param("p", anonymized.profile().value())
        .update();

    var stale = store.staleVersion(current.score().version().value(), 10);

    assertThat(stale).extracting(StoredScore::profile).containsExactly(old.profile());
    assertThat(stale.get(0).score().version().code()).isEqualTo("0");
  }

  @Test
  void deletingAndTouchingAPair() {
    var pair = aPair(ScoreReason.SUGGESTED);
    store.upsertCurrent(List.of(pair));

    store.touch(pair.profile(), pair.job(), T0.plusSeconds(500));
    assertThat(store.findCurrent(pair.profile(), pair.job()).orElseThrow().computedAt())
        .isEqualTo(T0.plusSeconds(500));
    assertThat(
            store.findCurrent(pair.profile(), pair.job()).orElseThrow().score().version().value())
        .isEqualTo(pair.score().version().value());

    store.deleteCurrent(pair.profile(), pair.job());
    assertThat(store.findCurrent(pair.profile(), pair.job())).isEmpty();
  }

  // ---- fotos y retención ----

  @Test
  void aSnapshotIsStoredAndOldOnesArePurged() {
    var pair = aPair(ScoreReason.SUGGESTED);
    store.insertSnapshot(
        new ScoreSnapshot(
            UUID.randomUUID(),
            pair.profile(),
            pair.job(),
            SnapshotReason.VERSION_CHANGED,
            pair.score(),
            T0.minus(Duration.ofDays(800))));
    store.insertSnapshot(
        new ScoreSnapshot(
            UUID.randomUUID(),
            pair.profile(),
            pair.job(),
            SnapshotReason.APPLIED,
            pair.score(),
            T0));

    int purged = store.purgeSnapshotsOlderThan(T0.minus(Duration.ofDays(730)));

    assertThat(purged).isEqualTo(1);
    assertThat(
            jdbc.sql("select reason from compatibility_score_snapshots").query(String.class).list())
        .containsExactly("APPLIED");
  }

  @Test
  void aSnapshotKeepsTheFullBreakdownToCompareVersions() {
    var pair = aPair(ScoreReason.SUGGESTED);
    store.insertSnapshot(
        new ScoreSnapshot(
            UUID.randomUUID(),
            pair.profile(),
            pair.job(),
            SnapshotReason.VERSION_CHANGED,
            pair.score(),
            T0));

    var breakdown =
        jdbc.sql("select breakdown::text from compatibility_score_snapshots")
            .query(String.class)
            .single();

    assertThat(breakdown).contains("SKILLS").contains("explanation");
  }

  // ---- copia del perfil al postular ----

  // Los pares postulados se encuentran por perfil y por vacante, también sin puntaje vigente.
  @Test
  void appliedPairsAreFoundFromBothSidesEvenWithoutACurrentScore() {
    StoredScore pair = aPair(ScoreReason.APPLIED);
    store.insertApplicationSnapshot(application(pair, UUID.randomUUID(), "perfil"));

    assertThat(store.appliedJobsOfProfile(pair.profile(), 10)).containsExactly(pair.job());
    assertThat(store.appliedProfilesOfJob(pair.job(), 10)).containsExactly(pair.profile());
    assertThat(store.appliedProfilesOfJob(pair.job(), 0)).isEmpty();
    assertThat(store.findCurrent(pair.profile(), pair.job())).isEmpty();
  }

  // El pipeline lee el vigente y el de postular de todos sus postulantes en una consulta cada uno.
  @Test
  void theCurrentAndTheAppliedScoreOfSeveralApplicantsAreReadTogether() {
    var job = world.job("PUBLISHED");
    var scoredCandidate = world.candidate("Java");
    var pendingCandidate = world.candidate("Go");
    StoredScore current = stored(scoredCandidate, job, 0.8, ScoreReason.APPLIED, T0);
    store.upsertCurrent(List.of(current));
    StoredScore atApplying = stored(scoredCandidate, job, 0.4, ScoreReason.APPLIED, T0);
    store.insertSnapshot(
        new ScoreSnapshot(
            UUID.randomUUID(),
            scoredCandidate.profileId(),
            job.jobId(),
            SnapshotReason.STAGE_CHANGED,
            current.score(),
            T0.plusSeconds(60)));
    store.insertSnapshot(
        new ScoreSnapshot(
            UUID.randomUUID(),
            scoredCandidate.profileId(),
            job.jobId(),
            SnapshotReason.APPLIED,
            atApplying.score(),
            T0));
    var both = List.of(scoredCandidate.profileId(), pendingCandidate.profileId());

    var currents = store.findCurrentForJob(job.jobId(), both);
    var applied = store.appliedOverallForJob(job.jobId(), both);

    assertThat(currents).containsOnlyKeys(scoredCandidate.profileId());
    assertThat(currents.get(scoredCandidate.profileId()).score().overall())
        .isCloseTo(current.score().overall(), org.assertj.core.data.Offset.offset(0.01));
    assertThat(applied).containsOnlyKeys(scoredCandidate.profileId());
    assertThat(applied.get(scoredCandidate.profileId()))
        .as("la foto de postular, no la del cambio de etapa")
        .isCloseTo(atApplying.score().overall(), org.assertj.core.data.Offset.offset(0.01));
    assertThat(store.findCurrentForJob(job.jobId(), List.of())).isEmpty();
    assertThat(store.appliedOverallForJob(job.jobId(), List.of())).isEmpty();
  }

  private ApplicationProfileSnapshot application(
      StoredScore pair, UUID applicationId, String text) {
    return new ApplicationProfileSnapshot(
        UUID.randomUUID(),
        pair.profile(),
        pair.job(),
        applicationId,
        text,
        "c".repeat(64),
        "1|m|aaaaaaaa|bbbbbbbb",
        T0);
  }

  @Test
  void theApplicationCopyIsIdempotentPerApplicationButPairsWithoutApplicationAreAllowed() {
    var pair = aPair(ScoreReason.APPLIED);
    var applicationId = UUID.randomUUID();

    store.insertApplicationSnapshot(application(pair, applicationId, "texto uno"));
    store.insertApplicationSnapshot(application(pair, applicationId, "texto dos"));
    store.insertApplicationSnapshot(application(pair, null, "sin postulación 1"));
    store.insertApplicationSnapshot(application(pair, null, "sin postulación 2"));

    assertThat(
            jdbc.sql("select count(*) from application_profile_snapshots")
                .query(Integer.class)
                .single())
        .isEqualTo(3);
    assertThat(
            jdbc.sql(
                    "select canonical_text from application_profile_snapshots where application_id = :a")
                .param("a", applicationId)
                .query(String.class)
                .single())
        .isEqualTo("texto uno");
  }

  // ---- anonimización (BR-DATA-05) ----

  @Test
  void anonymizingEmptiesWhatIsDerivedFromTheProfileAndKeepsThePairTheScoreAndTheVersion() {
    var pair = aPair(ScoreReason.APPLIED);
    var bystander = aPair(ScoreReason.APPLIED);
    store.upsertCurrent(List.of(pair, bystander));
    store.insertSnapshot(
        new ScoreSnapshot(
            UUID.randomUUID(),
            pair.profile(),
            pair.job(),
            SnapshotReason.APPLIED,
            pair.score(),
            T0));
    store.insertApplicationSnapshot(
        application(pair, UUID.randomUUID(), "Texto libre derivado de la persona"));

    store.anonymizeProfile(pair.profile(), T0.plusSeconds(60));

    var read = store.findCurrent(pair.profile(), pair.job()).orElseThrow();
    assertThat(read.score().breakdown().dimensions()).isEmpty();
    assertThat(read.score().overall())
        .isCloseTo(pair.score().overall(), org.assertj.core.data.Offset.offset(0.01));
    assertThat(read.score().version().value()).isEqualTo(pair.score().version().value());
    assertThat(
            jdbc.sql("select breakdown::text from compatibility_score_snapshots")
                .query(String.class)
                .single())
        .isEqualTo("{}");
    var copy =
        jdbc.sql(
                "select canonical_text, canonical_hash, anonymized_at from application_profile_snapshots")
            .query(
                (rows, n) -> new Object[] {rows.getString(1), rows.getString(2), rows.getObject(3)})
            .single();
    assertThat(copy[0]).isNull();
    assertThat(copy[1]).isNull();
    assertThat(copy[2]).isNotNull();
    // El de otra persona no se toca.
    assertThat(
            store
                .findCurrent(bystander.profile(), bystander.job())
                .orElseThrow()
                .score()
                .breakdown()
                .dimensions())
        .isNotEmpty();
  }

  @Test
  void anonymizingTwiceIsHarmless() {
    var pair = aPair(ScoreReason.APPLIED);
    store.upsertCurrent(List.of(pair));

    store.anonymizeProfile(pair.profile(), T0);
    store.anonymizeProfile(pair.profile(), T0.plusSeconds(1));

    assertThat(count()).isEqualTo(1);
  }
}
