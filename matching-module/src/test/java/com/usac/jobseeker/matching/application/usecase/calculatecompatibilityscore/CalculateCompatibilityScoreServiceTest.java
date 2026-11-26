package com.usac.jobseeker.matching.application.usecase.calculatecompatibilityscore;

import static org.assertj.core.api.Assertions.assertThat;

import com.usac.jobseeker.common.domain.model.AccountId;
import com.usac.jobseeker.matching.application.usecase.calculatecompatibilityscore.CalculatedScore.Status;
import com.usac.jobseeker.matching.domain.model.ScoreReason;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Puntuar un par: lee lo que ya existe, nunca llama a un modelo y nunca puntúa a medias (ADR-0017).
 */
class CalculateCompatibilityScoreServiceTest {

  private final PairFixtures world = new PairFixtures();
  private final CalculateCompatibilityScoreService service =
      new CalculateCompatibilityScoreService(
          world.sources,
          world.calculator,
          (useCase, outcome) -> world.metrics.add(outcome),
          Clock.fixed(PairFixtures.NOW, ZoneOffset.UTC));

  private CalculatedScore run(
      com.usac.jobseeker.matching.domain.model.CandidateSignals c,
      com.usac.jobseeker.matching.domain.model.JobSignals j,
      ScoreReason reason) {
    return service.execute(
        new CalculateCompatibilityScoreCommand(c.accountId(), j.jobId(), reason));
  }

  @Test
  void aPairWithBothSidesAndVectorsIsScoredAndStoredWithItsReason() {
    var candidate = world.candidate("Java");
    var job = world.job("PUBLISHED");
    world.similar(candidate, job, 0.8);

    var result = run(candidate, job, ScoreReason.CANDIDATE_VIEW);

    assertThat(result.status()).isEqualTo(Status.CALCULATED);
    assertThat(result.score().reason()).isEqualTo(ScoreReason.CANDIDATE_VIEW);
    assertThat(result.score().computedAt()).isEqualTo(PairFixtures.NOW);
    assertThat(result.score().score().breakdown().dimensions()).hasSize(6);
    assertThat(world.store.findCurrent(candidate.profileId(), job.jobId()))
        .contains(result.score());
    assertThat(world.metrics).containsExactly("calculated");
  }

  @Test
  void theScoreIsExactlyWhatTheCalculatorGives() {
    var candidate = world.candidate("Java");
    var job = world.job("PUBLISHED");
    world.similar(candidate, job, 0.8);

    var result = run(candidate, job, ScoreReason.CANDIDATE_VIEW);

    assertThat(result.score().score()).isEqualTo(world.calculator.score(candidate, job, 0.8));
  }

  @Test
  void anAccountWithoutAReadableProfileHasNothingToScore() {
    var job = world.job("PUBLISHED");

    var result =
        service.execute(
            new CalculateCompatibilityScoreCommand(
                new AccountId(UUID.randomUUID()), job.jobId(), ScoreReason.CANDIDATE_VIEW));

    assertThat(result.status()).isEqualTo(Status.NO_PROFILE);
    assertThat(result.score()).isNull();
    assertThat(world.store.current).isEmpty();
  }

  @Test
  void aJobThatCannotBeReadIsUnavailable() {
    var candidate = world.candidate("Java");
    var job = world.job("PUBLISHED");
    world.jobs.clear();

    assertThat(run(candidate, job, ScoreReason.SUGGESTED).status())
        .isEqualTo(Status.JOB_UNAVAILABLE);
  }

  // ADR-0017, decisión 1: falta un vector → pendiente, jamás un puntaje con un vector parcial.
  @Test
  void withoutASimilarityThePairIsPendingAndNothingIsStored() {
    var candidate = world.candidate("Java");
    var job = world.job("PUBLISHED");

    var result = run(candidate, job, ScoreReason.CANDIDATE_VIEW);

    assertThat(result.status()).isEqualTo(Status.PENDING_EMBEDDING);
    assertThat(world.store.current).isEmpty();
    assertThat(world.metrics).containsExactly("pending_embedding");
  }

  // NFR-AVAIL-02: si el par ya tenía puntaje y ahora falta un vector, se conserva el anterior.
  @Test
  void aPendingPairKeepsItsPreviousStoredScore() {
    var candidate = world.candidate("Java");
    var job = world.job("PUBLISHED");
    world.similar(candidate, job, 0.8);
    var first = run(candidate, job, ScoreReason.CANDIDATE_VIEW);
    world.cosines.clear();

    run(candidate, job, ScoreReason.CANDIDATE_VIEW);

    assertThat(world.store.findCurrent(candidate.profileId(), job.jobId())).contains(first.score());
  }

  @Test
  void aStrongerReasonIsNeverLoweredByAWeakerOne() {
    var candidate = world.candidate("Java");
    var job = world.job("PUBLISHED");
    world.similar(candidate, job, 0.8);
    run(candidate, job, ScoreReason.APPLIED);

    run(candidate, job, ScoreReason.SUGGESTED);

    assertThat(world.store.findCurrent(candidate.profileId(), job.jobId()).orElseThrow().reason())
        .isEqualTo(ScoreReason.APPLIED);
  }

  @Test
  void theMetricsCarryNoIdentifiers() {
    var candidate = world.candidate("Java");
    var job = world.job("PUBLISHED");
    world.similar(candidate, job, 0.5);

    run(candidate, job, ScoreReason.SUGGESTED);

    assertThat(world.metrics)
        .noneMatch(
            m ->
                m.contains(candidate.accountId().toString()) || m.contains(job.jobId().toString()));
  }
}
