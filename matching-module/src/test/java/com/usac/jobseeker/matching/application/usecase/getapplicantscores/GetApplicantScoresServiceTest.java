package com.usac.jobseeker.matching.application.usecase.getapplicantscores;

import static org.assertj.core.api.Assertions.assertThat;

import com.usac.jobseeker.matching.application.usecase.calculatecompatibilityscore.PairFixtures;
import com.usac.jobseeker.matching.domain.model.CandidateSignals;
import com.usac.jobseeker.matching.domain.model.JobSignals;
import com.usac.jobseeker.matching.domain.model.ScoreReason;
import com.usac.jobseeker.matching.domain.model.ScoreSnapshot;
import com.usac.jobseeker.matching.domain.model.SnapshotReason;
import com.usac.jobseeker.matching.domain.model.StoredScore;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Los puntajes del pipeline: el vigente con desglose, el de postular, o pendiente (TDD §9). */
class GetApplicantScoresServiceTest {

  private final PairFixtures world = new PairFixtures();
  private final GetApplicantScoresService service =
      new GetApplicantScoresService(world.store, (u, o) -> world.metrics.add(o));

  private StoredScore scored(CandidateSignals candidate, JobSignals job, double cosine) {
    StoredScore stored =
        new StoredScore(
            candidate.profileId(),
            job.jobId(),
            world.calculator.score(candidate, job, cosine),
            ScoreReason.APPLIED,
            PairFixtures.NOW);
    world.store.put(stored);
    return stored;
  }

  @Test
  void eachRequestedApplicantComesInOrderWithItsScoreOrPending() {
    JobSignals job = world.job("PUBLISHED");
    CandidateSignals scored = world.candidate("Java");
    CandidateSignals pending = world.candidate("Go");
    StoredScore stored = scored(scored, job, 0.8);
    world.store.insertSnapshot(
        new ScoreSnapshot(
            UUID.randomUUID(),
            scored.profileId(),
            job.jobId(),
            SnapshotReason.APPLIED,
            world.calculator.score(scored, job, 0.5),
            PairFixtures.NOW));

    List<ApplicantScore> result =
        service.execute(
            new GetApplicantScoresQuery(
                job.jobId().value(),
                List.of(pending.profileId().value(), scored.profileId().value())));

    assertThat(result)
        .extracting(ApplicantScore::candidateProfileId)
        .containsExactly(pending.profileId().value(), scored.profileId().value());
    ApplicantScore none = result.get(0);
    assertThat(none.status()).isEqualTo(ApplicantScore.PENDING);
    assertThat(none.overall()).as("nunca un número a medias").isNull();
    assertThat(none.breakdown()).isNull();
    ApplicantScore ok = result.get(1);
    assertThat(ok.status()).isEqualTo(ApplicantScore.OK);
    assertThat(ok.overall()).isEqualTo(stored.score().overall());
    assertThat(ok.calibrated()).isEqualTo(stored.score().calibrated());
    assertThat(ok.algorithmVersion()).isEqualTo(stored.score().version().value());
    assertThat(ok.appliedOverall())
        .as("el de postular, distinto del vigente")
        .isEqualTo(world.calculator.score(scored, job, 0.5).overall());
    assertThat(ok.breakdown().dimensions()).hasSize(stored.score().breakdown().dimensions().size());
  }

  @Test
  void noApplicantsReadsNothing() {
    assertThat(
            service.execute(
                new GetApplicantScoresQuery(world.job("PUBLISHED").jobId().value(), List.of())))
        .isEmpty();
    assertThat(world.metrics).isEmpty();
  }
}
