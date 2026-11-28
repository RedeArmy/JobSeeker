package com.usac.jobseeker.matching.application.usecase.recomputescoresforentity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.usac.jobseeker.matching.application.usecase.calculatecompatibilityscore.PairFixtures;
import com.usac.jobseeker.matching.application.usecase.embedentity.EmbeddingEntity;
import com.usac.jobseeker.matching.domain.model.CandidateSignals;
import com.usac.jobseeker.matching.domain.model.JobSignals;
import com.usac.jobseeker.matching.domain.model.ScoreReason;
import com.usac.jobseeker.matching.domain.model.StoredScore;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

/** BR-MATCH-03: tras cambiar un perfil o una vacante, sus puntajes guardados se ponen al día. */
class RecomputeScoresForEntityServiceTest {

  private static final Instant OLD = Instant.parse("2026-11-01T00:00:00Z");

  private final PairFixtures world = new PairFixtures();
  private int maxPairs = 200;

  private RecomputeScoresForEntityService service() {
    return new RecomputeScoresForEntityService(
        world.sources,
        world.calculator,
        maxPairs,
        (useCase, outcome) -> world.metrics.add(outcome),
        Clock.fixed(PairFixtures.NOW, ZoneOffset.UTC));
  }

  /** Un par con un puntaje viejo guardado (otra cosine) para ver que se reemplaza. */
  private StoredScore stored(CandidateSignals candidate, JobSignals job, ScoreReason reason) {
    world.similar(candidate, job, 0.2);
    var old =
        new StoredScore(
            candidate.profileId(),
            job.jobId(),
            world.calculator.score(candidate, job, 0.2),
            reason,
            OLD);
    world.store.put(old);
    return old;
  }

  private int recomputeProfile(CandidateSignals candidate) {
    return service()
        .execute(
            new RecomputeScoresCommand(EmbeddingEntity.PROFILE, candidate.accountId().value()));
  }

  private int recomputeJob(JobSignals job) {
    return service().execute(new RecomputeScoresCommand(EmbeddingEntity.JOB, job.jobId().value()));
  }

  @Test
  void whenAProfileChangesEveryStoredPairOfThatProfileIsRecomputed() {
    var candidate = world.candidate("Java");
    var first = world.job("PUBLISHED");
    var second = world.job("PUBLISHED");
    stored(candidate, first, ScoreReason.SUGGESTED);
    stored(candidate, second, ScoreReason.CANDIDATE_VIEW);
    world.similar(candidate, first, 0.9);
    world.similar(candidate, second, 0.7);

    assertThat(recomputeProfile(candidate)).isEqualTo(2);

    var fresh = world.store.findCurrent(candidate.profileId(), first.jobId()).orElseThrow();
    assertThat(fresh.score()).isEqualTo(world.calculator.score(candidate, first, 0.9));
    assertThat(fresh.computedAt()).isEqualTo(PairFixtures.NOW);
    assertThat(world.metrics).containsExactly("recomputed");
  }

  /** Lo que deja postular cuando aún no hay vector: la copia del perfil, sin puntaje. */
  private void appliedWithoutScore(CandidateSignals candidate, JobSignals job) {
    world.store.insertApplicationSnapshot(
        new com.usac.jobseeker.matching.domain.model.ApplicationProfileSnapshot(
            java.util.UUID.randomUUID(),
            candidate.profileId(),
            job.jobId(),
            java.util.UUID.randomUUID(),
            "perfil",
            "c".repeat(64),
            "v1",
            OLD));
  }

  // ADR-0031: si al postular faltaba el vector, el puntaje nace cuando llega, y como APPLIED; antes
  // el recálculo solo recorría pares con puntaje y la postulación quedaba pendiente para siempre.
  @Test
  void anAppliedPairWithoutAScoreGetsOneAsAppliedWhenItsProfileIsRecomputed() {
    var candidate = world.candidate("Java");
    var job = world.job("PUBLISHED");
    appliedWithoutScore(candidate, job);
    world.similar(candidate, job, 0.8);

    assertThat(recomputeProfile(candidate)).isEqualTo(1);

    var created = world.store.findCurrent(candidate.profileId(), job.jobId()).orElseThrow();
    assertThat(created.reason()).isEqualTo(ScoreReason.APPLIED);
    assertThat(created.score()).isEqualTo(world.calculator.score(candidate, job, 0.8));
  }

  @Test
  void anAppliedPairWithoutAScoreGetsOneAsAppliedWhenItsJobIsRecomputed() {
    var candidate = world.candidate("Java");
    var job = world.job("PUBLISHED");
    var suggested = world.candidate("Go");
    appliedWithoutScore(candidate, job);
    stored(suggested, job, ScoreReason.SUGGESTED);
    world.similar(candidate, job, 0.8);

    assertThat(recomputeJob(job)).isEqualTo(2);

    assertThat(world.store.findCurrent(candidate.profileId(), job.jobId()).orElseThrow().reason())
        .isEqualTo(ScoreReason.APPLIED);
    assertThat(world.store.findCurrent(suggested.profileId(), job.jobId()).orElseThrow().reason())
        .as("un sugerido sigue sugerido")
        .isEqualTo(ScoreReason.SUGGESTED);
  }

  @Test
  void whenAJobChangesEveryStoredPairOfThatJobIsRecomputedWithEachCandidateOwnSignals() {
    var job = world.job("PUBLISHED");
    var a = world.candidate("Java");
    var b = world.candidate("Go");
    stored(a, job, ScoreReason.SUGGESTED);
    stored(b, job, ScoreReason.SUGGESTED);
    world.similar(a, job, 0.9);
    world.similar(b, job, 0.6);

    assertThat(recomputeJob(job)).isEqualTo(2);

    assertThat(world.store.findCurrent(a.profileId(), job.jobId()).orElseThrow().score())
        .isEqualTo(world.calculator.score(a, job, 0.9));
    assertThat(world.store.findCurrent(b.profileId(), job.jobId()).orElseThrow().score())
        .isEqualTo(world.calculator.score(b, job, 0.6));
  }

  // El motivo más fuerte sobrevive al recálculo: un postulado sigue postulado.
  @Test
  void aRecomputeNeverLowersTheReasonOfAPair() {
    var candidate = world.candidate("Java");
    var job = world.job("PUBLISHED");
    stored(candidate, job, ScoreReason.APPLIED);
    world.similar(candidate, job, 0.9);

    recomputeProfile(candidate);

    assertThat(world.store.findCurrent(candidate.profileId(), job.jobId()).orElseThrow().reason())
        .isEqualTo(ScoreReason.APPLIED);
  }

  @Test
  void aRecomputeDoesNotCreatePairsThatNobodyHadAReasonFor() {
    var candidate = world.candidate("Java");
    var job = world.job("PUBLISHED");
    world.similar(candidate, job, 0.9);

    assertThat(recomputeProfile(candidate)).isZero();

    assertThat(world.store.current).isEmpty();
    assertThat(world.metrics).containsExactly("nothing");
  }

  // NFR-AVAIL-02: lo que no se puede recalcular se deja como está, nunca a medias.
  @Test
  void aPairWhoseVectorIsMissingKeepsItsLastScore() {
    var candidate = world.candidate("Java");
    var job = world.job("PUBLISHED");
    var old = stored(candidate, job, ScoreReason.SUGGESTED);
    world.cosines.clear();

    assertThat(recomputeProfile(candidate)).isZero();

    assertThat(world.store.findCurrent(candidate.profileId(), job.jobId())).contains(old);
  }

  @Test
  void aPairWhoseJobCannotBeReadKeepsItsLastScoreAndOthersAreStillRecomputed() {
    var candidate = world.candidate("Java");
    var gone = world.job("PUBLISHED");
    var alive = world.job("PUBLISHED");
    var old = stored(candidate, gone, ScoreReason.SUGGESTED);
    stored(candidate, alive, ScoreReason.SUGGESTED);
    world.similar(candidate, alive, 0.9);
    world.jobs.remove(gone.jobId());

    assertThat(recomputeProfile(candidate)).isEqualTo(1);

    assertThat(world.store.findCurrent(candidate.profileId(), gone.jobId())).contains(old);
  }

  @Test
  void anAccountWithoutAKnownProfileOrWithoutReadableSignalsRecomputesNothing() {
    var candidate = world.candidate("Java");
    var job = world.job("PUBLISHED");
    stored(candidate, job, ScoreReason.SUGGESTED);
    world.candidates.clear();

    assertThat(recomputeProfile(candidate)).isZero();
    world.accounts.clear();
    assertThat(recomputeProfile(candidate)).isZero();
  }

  @Test
  void aJobThatCannotBeReadRecomputesNothing() {
    var candidate = world.candidate("Java");
    var job = world.job("PUBLISHED");
    stored(candidate, job, ScoreReason.SUGGESTED);
    world.jobs.clear();

    assertThat(recomputeJob(job)).isZero();
  }

  // Una entidad con muchísimos pares no puede retener al trabajador.
  @Test
  void theNumberOfPairsPerEntityIsCappedMostRecentFirst() {
    var job = world.job("PUBLISHED");
    var candidates = new java.util.ArrayList<CandidateSignals>();
    for (int i = 0; i < 5; i++) {
      var c = world.candidate("Java");
      var old = stored(c, job, ScoreReason.SUGGESTED);
      world.store.put(
          new StoredScore(old.profile(), old.job(), old.score(), old.reason(), OLD.plusSeconds(i)));
      world.similar(c, job, 0.9);
      candidates.add(c);
    }
    maxPairs = 2;

    assertThat(recomputeJob(job)).isEqualTo(2);

    // Los dos más recientes (los últimos creados) son los recalculados.
    assertThat(
            world
                .store
                .findCurrent(candidates.get(4).profileId(), job.jobId())
                .orElseThrow()
                .computedAt())
        .isEqualTo(PairFixtures.NOW);
    assertThat(
            world
                .store
                .findCurrent(candidates.get(0).profileId(), job.jobId())
                .orElseThrow()
                .computedAt())
        .isEqualTo(OLD);
  }

  @Test
  void theCandidatesOfAJobAreReadWithOneBatchNotOneByOne() {
    var job = world.job("PUBLISHED");
    for (int i = 0; i < 6; i++) {
      var c = world.candidate("Java");
      stored(c, job, ScoreReason.SUGGESTED);
      world.similar(c, job, 0.5);
    }
    world.candidateReads = 0;

    recomputeJob(job);

    assertThat(world.candidateReads).isEqualTo(1);
  }

  @Test
  void theCapMustBePositive() {
    maxPairs = 0;

    assertThatThrownBy(this::service).isInstanceOf(IllegalArgumentException.class);
  }
}
