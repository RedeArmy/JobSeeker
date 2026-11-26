package com.usac.jobseeker.matching.application.usecase.explainscore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.usac.jobseeker.common.domain.error.ForbiddenCapabilityException;
import com.usac.jobseeker.common.domain.error.InvalidStateException;
import com.usac.jobseeker.common.domain.error.NotFoundException;
import com.usac.jobseeker.common.domain.error.ValidationFailedException;
import com.usac.jobseeker.common.domain.model.AccountId;
import com.usac.jobseeker.matching.application.usecase.calculatecompatibilityscore.CalculateCompatibilityScoreService;
import com.usac.jobseeker.matching.application.usecase.calculatecompatibilityscore.PairFixtures;
import com.usac.jobseeker.matching.application.usecase.embedentity.EmbeddingEntity;
import com.usac.jobseeker.matching.application.usecase.embedentity.EmbeddingWorkQueuePort;
import com.usac.jobseeker.matching.application.usecase.suggestcandidatesforjob.OwnedJobPort;
import com.usac.jobseeker.matching.domain.model.CandidateSignals;
import com.usac.jobseeker.matching.domain.model.Dimension;
import com.usac.jobseeker.matching.domain.model.JobPostingId;
import com.usac.jobseeker.matching.domain.model.JobSignals;
import com.usac.jobseeker.matching.domain.model.ScoreReason;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * El candidato ve su compatibilidad solo con vacantes que puede ver (BR-VIS-01, BR-JOB-11,
 * BR-ACCT-05) y puede probar un «qué pasaría si» (REQ-C-01) con las mismas reglas y sin guardar
 * nada.
 */
class ExplainScoreAndWhatIfServicesTest {

  private final PairFixtures world = new PairFixtures();
  private final Set<AccountId> candidates = new HashSet<>();
  private final Set<String> owners = new HashSet<>();
  private final Set<String> pending = new HashSet<>();

  private final CandidateJobAccess access =
      new CandidateJobAccess(
          candidates::contains,
          (owner, job) ->
              owners.contains(owner.value() + ":" + job.value())
                  ? Optional.of(new OwnedJobPort.OwnedJob(true, false))
                  : Optional.empty(),
          id -> {
            world.jobReads++;
            return Optional.ofNullable(world.jobs.get(id));
          });

  private final EmbeddingWorkQueuePort queue =
      new EmbeddingWorkQueuePort() {
        public void request(EmbeddingEntity entity, UUID key, Instant notBefore) {}

        public List<Job> claim(int limit, Duration lease, int maxAttempts) {
          return List.of();
        }

        public void complete(Job job) {}

        public void retryAt(Job job, Instant notBefore) {}

        public void fail(Job job) {}

        public void discard(EmbeddingEntity entity, UUID key) {}

        @Override
        public boolean isPending(EmbeddingEntity entity, UUID key) {
          return pending.contains(entity + ":" + key);
        }
      };

  private final Clock clock = Clock.fixed(PairFixtures.NOW, ZoneOffset.UTC);
  private final ExplainScoreService explain =
      new ExplainScoreService(
          access,
          new CalculateCompatibilityScoreService(
              world.sources, world.calculator, (u, o) -> {}, clock),
          queue,
          (useCase, outcome) -> world.metrics.add(outcome));
  private final WhatIfScoreService whatIf =
      new WhatIfScoreService(
          access,
          world.sources,
          world.calculator,
          (useCase, outcome) -> world.metrics.add(outcome));

  private CandidateSignals candidate() {
    var c = world.candidate("Java");
    candidates.add(c.accountId());
    return c;
  }

  private ExplainScoreResult explain(CandidateSignals c, JobSignals j) {
    return explain.execute(new ExplainScoreQuery(c.accountId(), j.jobId()));
  }

  private WhatIfScoreResult whatIf(
      CandidateSignals c,
      JobSignals j,
      List<CandidateSignals.Skill> skills,
      List<CandidateSignals.Language> languages) {
    return whatIf.execute(new WhatIfScoreQuery(c.accountId(), j.jobId(), skills, languages));
  }

  // ---- visibilidad ----

  @Test
  void aCandidateSeesTheScoreOfAPublishedJobAndItIsStoredAsViewedByTheCandidate() {
    var c = candidate();
    var job = world.job("PUBLISHED");
    world.similar(c, job, 0.8);

    var result = explain(c, job);

    assertThat(result.status()).isEqualTo(ExplainScoreResult.Status.OK);
    assertThat(result.score()).isEqualTo(world.calculator.score(c, job, 0.8));
    assertThat(result.score().breakdown().dimensions()).hasSize(6);
    assertThat(world.store.findCurrent(c.profileId(), job.jobId()).orElseThrow().reason())
        .isEqualTo(ScoreReason.CANDIDATE_VIEW);
    assertThat(world.metrics).contains("ok");
  }

  // BR-JOB-11: las cerradas se ven, marcadas; un borrador, archivada o eliminada nunca.
  @Test
  void aClosedJobIsVisibleButADraftOrArchivedOneIsNot() {
    var c = candidate();
    var closed = world.job("CLOSED");
    world.similar(c, closed, 0.5);
    assertThat(explain(c, closed).status()).isEqualTo(ExplainScoreResult.Status.OK);

    for (String status : List.of("DRAFT", "ARCHIVED", "DELETED", "RARO")) {
      var hidden = world.job(status);
      world.similar(c, hidden, 0.5);
      assertThatThrownBy(() -> explain(c, hidden)).isInstanceOf(NotFoundException.class);
    }
  }

  @Test
  void anAccountWithoutTheCandidateCapabilityIsForbidden() {
    var c = world.candidate("Java");
    var job = world.job("PUBLISHED");

    assertThatThrownBy(() -> explain(c, job)).isInstanceOf(ForbiddenCapabilityException.class);
    assertThatThrownBy(() -> whatIf(c, job, List.of(), List.of()))
        .isInstanceOf(ForbiddenCapabilityException.class);
  }

  // BR-ACCT-05: un reclutador no postula, como candidato, a su propia vacante.
  @Test
  void theOwnerOfAJobCannotSeeItsScoreAsACandidate() {
    var c = candidate();
    var job = world.job("PUBLISHED");
    world.similar(c, job, 0.8);
    owners.add(c.accountId().value() + ":" + job.jobId().value());

    assertThatThrownBy(() -> explain(c, job)).isInstanceOf(NotFoundException.class);
    assertThatThrownBy(() -> whatIf(c, job, List.of(), List.of()))
        .isInstanceOf(NotFoundException.class);
  }

  @Test
  void anUnknownJobDoesNotExistForTheCandidate() {
    var c = candidate();
    var job = world.job("PUBLISHED");
    world.jobs.clear();

    assertThatThrownBy(() -> explain(c, job)).isInstanceOf(NotFoundException.class);
  }

  @Test
  void aCandidateWithoutAReadableProfileGetsAnInvalidState() {
    var c = candidate();
    var job = world.job("PUBLISHED");
    world.candidates.clear();

    assertThatThrownBy(() -> explain(c, job)).isInstanceOf(InvalidStateException.class);
    assertThatThrownBy(() -> whatIf(c, job, List.of(), List.of()))
        .isInstanceOf(InvalidStateException.class);
  }

  // ---- pendiente y «actualizando…» ----

  @Test
  void withoutAVectorTheAnswerIsPendingAndNeverAPartialScore() {
    var c = candidate();
    var job = world.job("PUBLISHED");

    var result = explain(c, job);

    assertThat(result.status()).isEqualTo(ExplainScoreResult.Status.PENDING_EMBEDDING);
    assertThat(result.score()).isNull();
    assertThat(result.updating()).isTrue();
    assertThat(world.store.current).isEmpty();
  }

  // BR-MATCH-03: mientras hay un cambio en cola la interfaz dice «actualizando…».
  @Test
  void aQueuedChangeOfTheProfileOrOfTheJobMarksTheScoreAsUpdating() {
    var c = candidate();
    var job = world.job("PUBLISHED");
    world.similar(c, job, 0.8);

    assertThat(explain(c, job).updating()).isFalse();
    pending.add(EmbeddingEntity.PROFILE + ":" + c.accountId().value());
    assertThat(explain(c, job).updating()).isTrue();
    pending.clear();
    pending.add(EmbeddingEntity.JOB + ":" + job.jobId().value());
    assertThat(explain(c, job).updating()).isTrue();
  }

  // ---- qué pasaría si ----

  @Test
  void declaringARequiredSkillRaisesTheHypotheticalScoreAndNothingIsStored() {
    var candidate = candidate();
    var job = world.job("PUBLISHED");
    world.similar(candidate, job, 0.5);
    var bare = world.candidate("Python");
    candidates.add(bare.accountId());
    world.similar(bare, job, 0.5);

    var result = whatIf(bare, job, List.of(new CandidateSignals.Skill("Java", 3)), List.of());

    assertThat(result.status()).isEqualTo(WhatIfScoreResult.Status.OK);
    assertThat(result.hypothetical().overall()).isGreaterThan(result.current().overall());
    assertThat(result.current()).isEqualTo(world.calculator.score(bare, job, 0.5));
    assertThat(world.store.current).isEmpty();
    assertThat(world.store.upsertCalls).isZero();
  }

  @Test
  void anEmptyHypothesisGivesTheSameScoreAsToday() {
    var c = candidate();
    var job = world.job("PUBLISHED");
    world.similar(c, job, 0.5);

    var result = whatIf(c, job, List.of(), List.of());

    assertThat(result.hypothetical()).isEqualTo(result.current());
  }

  @Test
  void whatIfIsPendingWithoutAVector() {
    var c = candidate();
    var job = world.job("PUBLISHED");

    var result = whatIf(c, job, List.of(), List.of());

    assertThat(result.status()).isEqualTo(WhatIfScoreResult.Status.PENDING_EMBEDDING);
    assertThat(result.current()).isNull();
  }

  @Test
  void theCurrentScoreOfAWhatIfIsExactlyTheOneOfTheRealQuery() {
    var c = candidate();
    var job = world.job("PUBLISHED");
    world.similar(c, job, 0.7);

    assertThat(whatIf(c, job, List.of(new CandidateSignals.Skill("Go", 2)), List.of()).current())
        .isEqualTo(explain(c, job).score());
  }

  // ---- validación de la hipótesis ----

  @Test
  void aWhatIfQueryRejectsTooManyBlankOrOutOfRangeHypotheses() {
    var account = new AccountId(UUID.randomUUID());
    var job = new JobPostingId(UUID.randomUUID());
    var many =
        java.util.stream.IntStream.range(0, WhatIfScoreQuery.MAX_EXTRAS + 1)
            .mapToObj(i -> new CandidateSignals.Skill("S" + i, 1))
            .toList();

    assertThatThrownBy(() -> new WhatIfScoreQuery(account, job, many, List.of()))
        .isInstanceOf(ValidationFailedException.class);
    assertThatThrownBy(
            () ->
                new WhatIfScoreQuery(
                    account, job, List.of(new CandidateSignals.Skill(" ", 1)), List.of()))
        .isInstanceOf(ValidationFailedException.class);
    assertThatThrownBy(
            () ->
                new WhatIfScoreQuery(
                    account, job, List.of(new CandidateSignals.Skill("Java", 9)), List.of()))
        .isInstanceOf(ValidationFailedException.class);
    assertThatThrownBy(
            () ->
                new WhatIfScoreQuery(
                    account, job, List.of(), List.of(new CandidateSignals.Language("en", "Z9"))))
        .isInstanceOf(ValidationFailedException.class);
    assertThatThrownBy(
            () ->
                new WhatIfScoreQuery(
                    account, job, List.of(), List.of(new CandidateSignals.Language("", "B2"))))
        .isInstanceOf(ValidationFailedException.class);
  }

  @Test
  void nullListsAreEmptyHypotheses() {
    var query =
        new WhatIfScoreQuery(
            new AccountId(UUID.randomUUID()), new JobPostingId(UUID.randomUUID()), null, null);

    assertThat(query.skills()).isEmpty();
    assertThat(query.languages()).isEmpty();
  }

  // ---- la copia hipotética ----

  @Test
  void anAdditionalSkillReplacesTheSameNameAndNothingElseChanges() {
    var c = candidate();

    var changed =
        c.withAdditional(
            List.of(new CandidateSignals.Skill("java", 5), new CandidateSignals.Skill("Go", 2)),
            List.of(new CandidateSignals.Language("en", "B2")));

    assertThat(changed.skills())
        .extracting(CandidateSignals.Skill::name)
        .containsExactlyInAnyOrder("java", "Go");
    assertThat(changed.skills())
        .filteredOn(s -> s.name().equals("java"))
        .singleElement()
        .satisfies(s -> assertThat(s.level()).isEqualTo(5));
    assertThat(changed.languages()).hasSize(1);
    assertThat(changed.profileId()).isEqualTo(c.profileId());
    assertThat(changed.headline()).isEqualTo(c.headline());
    assertThat(c.skills()).hasSize(1);
    assertThat(Dimension.values()).hasSize(6);
  }
}
