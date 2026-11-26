package com.usac.jobseeker.matching.application.usecase.migratealgorithmversion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.usac.jobseeker.matching.application.usecase.calculatecompatibilityscore.PairFixtures;
import com.usac.jobseeker.matching.application.usecase.calculatecompatibilityscore.ScoreCalculator;
import com.usac.jobseeker.matching.application.usecase.suggestcandidatesforjob.ScoringModel;
import com.usac.jobseeker.matching.domain.model.Calibration;
import com.usac.jobseeker.matching.domain.model.CandidateSignals;
import com.usac.jobseeker.matching.domain.model.JobSignals;
import com.usac.jobseeker.matching.domain.model.ScoreReason;
import com.usac.jobseeker.matching.domain.model.SnapshotReason;
import com.usac.jobseeker.matching.domain.model.StoredScore;
import com.usac.jobseeker.matching.domain.model.Weights;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

/**
 * ADR-0017, decisión 10: cambiar la versión del algoritmo migra por lotes y conserva la foto
 * anterior.
 */
class MigrateAlgorithmVersionServiceTest {

  private static final Instant OLD = Instant.parse("2026-10-01T00:00:00Z");

  private final PairFixtures world = new PairFixtures();
  private int batch = 100;

  private MigrateAlgorithmVersionService service() {
    return new MigrateAlgorithmVersionService(
        world.sources,
        world.calculator,
        batch,
        Duration.ofDays(730),
        (useCase, outcome) -> world.metrics.add(outcome),
        Clock.fixed(PairFixtures.NOW, ZoneOffset.UTC));
  }

  /** El puntaje guardado con una versión anterior del algoritmo (otro `α`). */
  private StoredScore stale(CandidateSignals candidate, JobSignals job, ScoreReason reason) {
    var older =
        new ScoreCalculator(
            new ScoringModel(Weights.defaults(), Calibration.uncalibrated("modelo-viejo")),
            Clock.fixed(PairFixtures.NOW, ZoneOffset.UTC));
    var old =
        new StoredScore(
            candidate.profileId(), job.jobId(), older.score(candidate, job, 0.3), reason, OLD);
    world.store.put(old);
    world.similar(candidate, job, 0.8);
    return old;
  }

  @Test
  void aStalePairGetsASnapshotOfItsOldScoreAndThenTheCurrentVersion() {
    var candidate = world.candidate("Java");
    var job = world.job("PUBLISHED");
    var old = stale(candidate, job, ScoreReason.SUGGESTED);

    assertThat(service().execute()).isEqualTo(1);
    assertThat(world.metrics).containsExactly("migrated");

    // La foto es la del puntaje ANTERIOR: es lo que permite comparar una versión con otra.
    assertThat(world.store.snapshots)
        .singleElement()
        .satisfies(
            s -> {
              assertThat(s.reason()).isEqualTo(SnapshotReason.VERSION_CHANGED);
              assertThat(s.score()).isEqualTo(old.score());
              assertThat(s.createdAt()).isEqualTo(PairFixtures.NOW);
            });
    var fresh = world.store.findCurrent(candidate.profileId(), job.jobId()).orElseThrow();
    assertThat(fresh.score().version()).isEqualTo(world.calculator.version());
    assertThat(fresh.score()).isEqualTo(world.calculator.score(candidate, job, 0.8));
    assertThat(fresh.reason()).isEqualTo(ScoreReason.SUGGESTED);
  }

  @Test
  void whatIsAlreadyOnTheCurrentVersionIsNotTouched() {
    var candidate = world.candidate("Java");
    var job = world.job("PUBLISHED");
    world.similar(candidate, job, 0.8);
    world.store.put(
        new StoredScore(
            candidate.profileId(),
            job.jobId(),
            world.calculator.score(candidate, job, 0.8),
            ScoreReason.SUGGESTED,
            OLD));

    assertThat(service().execute()).isZero();

    assertThat(world.store.snapshots).isEmpty();
    assertThat(world.metrics).isEmpty();
  }

  @Test
  void theReasonOfTheOldScoreIsKept() {
    var candidate = world.candidate("Java");
    var job = world.job("PUBLISHED");
    stale(candidate, job, ScoreReason.APPLIED);

    service().execute();

    assertThat(world.store.findCurrent(candidate.profileId(), job.jobId()).orElseThrow().reason())
        .isEqualTo(ScoreReason.APPLIED);
  }

  // Lo que no se puede recalcular no debe ocupar el primer lugar en cada pasada.
  @Test
  void aPairWhoseJobIsGoneIsRetiredUnlessItWasApplied() {
    var gone = world.candidate("Java");
    var goneJob = world.job("PUBLISHED");
    stale(gone, goneJob, ScoreReason.SUGGESTED);
    var applied = world.candidate("Java");
    var appliedJob = world.job("PUBLISHED");
    stale(applied, appliedJob, ScoreReason.APPLIED);
    world.jobs.clear();

    assertThat(service().execute()).isEqualTo(2);

    assertThat(world.store.findCurrent(gone.profileId(), goneJob.jobId())).isEmpty();
    // Un postulado se conserva (BR-JOB-09, BR-DATA-05) y pasa al final de la cola.
    assertThat(world.store.findCurrent(applied.profileId(), appliedJob.jobId())).isPresent();
    assertThat(world.store.touched)
        .containsExactly(applied.profileId().value() + ":" + appliedJob.jobId().value());
    assertThat(world.store.snapshots).isEmpty();
  }

  @Test
  void aPairWithoutAReadableProfileIsHandledTheSameWay() {
    var candidate = world.candidate("Java");
    var job = world.job("PUBLISHED");
    stale(candidate, job, ScoreReason.CANDIDATE_VIEW);
    world.accounts.clear();

    service().execute();

    assertThat(world.store.deleted).hasSize(1);
  }

  @Test
  void aPairWhoseVectorIsMissingIsDeferredNotRetiredNorMigratedHalfway() {
    var candidate = world.candidate("Java");
    var job = world.job("PUBLISHED");
    var old = stale(candidate, job, ScoreReason.SUGGESTED);
    world.cosines.clear();

    service().execute();

    assertThat(world.store.findCurrent(candidate.profileId(), job.jobId()).orElseThrow().score())
        .isEqualTo(old.score());
    assertThat(world.store.touched).hasSize(1);
    assertThat(world.store.snapshots).isEmpty();
    assertThat(world.metrics).containsExactly("deferred");
  }

  @Test
  void aPassTakesAtMostABatchOldestFirst() {
    batch = 2;
    for (int i = 0; i < 4; i++) {
      var candidate = world.candidate("Java");
      var job = world.job("PUBLISHED");
      var old = stale(candidate, job, ScoreReason.SUGGESTED);
      world.store.put(
          new StoredScore(old.profile(), old.job(), old.score(), old.reason(), OLD.plusSeconds(i)));
    }

    assertThat(service().execute()).isEqualTo(2);
    assertThat(service().execute()).isEqualTo(2);
    assertThat(service().execute()).isZero();
  }

  @Test
  void expiredSnapshotsArePurgedBeforeTheRetentionLimit() {
    world.store.purged = 7;

    assertThat(service().purgeExpiredSnapshots()).isEqualTo(7);

    assertThat(world.store.purgedBefore).isEqualTo(PairFixtures.NOW.minus(Duration.ofDays(730)));
    assertThat(world.metrics).containsExactly("purged");
  }

  @Test
  void aPurgeThatDeletesNothingReportsNothing() {
    assertThat(service().purgeExpiredSnapshots()).isZero();
    assertThat(world.metrics).isEmpty();
  }

  @Test
  void theBatchSizeMustBePositive() {
    batch = 0;

    assertThatThrownBy(this::service).isInstanceOf(IllegalArgumentException.class);
  }
}
