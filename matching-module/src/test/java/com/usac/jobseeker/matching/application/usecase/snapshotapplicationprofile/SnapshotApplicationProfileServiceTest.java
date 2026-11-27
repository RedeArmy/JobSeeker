package com.usac.jobseeker.matching.application.usecase.snapshotapplicationprofile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.usac.jobseeker.common.domain.error.InvalidStateException;
import com.usac.jobseeker.common.domain.model.AccountId;
import com.usac.jobseeker.matching.application.usecase.calculatecompatibilityscore.CalculateCompatibilityScoreService;
import com.usac.jobseeker.matching.application.usecase.calculatecompatibilityscore.PairFixtures;
import com.usac.jobseeker.matching.domain.model.JobPostingId;
import com.usac.jobseeker.matching.domain.model.ScoreReason;
import com.usac.jobseeker.matching.domain.model.SnapshotReason;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * ADR-0017, decisión 9: el reclutador evalúa lo que el candidato presentó, no lo que edite después.
 */
class SnapshotApplicationProfileServiceTest {

  private final PairFixtures world = new PairFixtures();
  private final Clock clock = Clock.fixed(PairFixtures.NOW, ZoneOffset.UTC);
  private final SnapshotApplicationProfileService service =
      new SnapshotApplicationProfileService(
          world.sources,
          new CalculateCompatibilityScoreService(
              world.sources, world.calculator, (u, o) -> {}, clock),
          world.calculator,
          4000,
          (useCase, outcome) -> world.metrics.add(outcome),
          clock);

  private SnapshotApplicationProfileUseCase.SnapshotResult snapshot(
      AccountId account, JobPostingId job, UUID application, SnapshotReason reason) {
    return service.execute(
        new SnapshotApplicationProfileCommand(
            account,
            job.value(),
            application,
            SnapshotApplicationProfileCommand.Reason.valueOf(reason.name())));
  }

  @Test
  void applyingStoresTheCanonicalProfileTheScoreSnapshotAndTheAppliedCurrentScore() {
    var candidate = world.candidate("Java");
    var job = world.job("PUBLISHED");
    world.similar(candidate, job, 0.8);
    var application = UUID.randomUUID();

    var result = snapshot(candidate.accountId(), job.jobId(), application, SnapshotReason.APPLIED);

    assertThat(result.profileSnapshotted()).isTrue();
    assertThat(result.scoreSnapshotted()).isTrue();
    assertThat(world.store.applicationSnapshots)
        .singleElement()
        .satisfies(
            s -> {
              assertThat(s.applicationId()).isEqualTo(application);
              assertThat(s.canonicalHash()).matches("[0-9a-f]{64}");
              assertThat(s.canonicalText()).isNotBlank();
              assertThat(s.algorithmVersion()).isEqualTo(world.calculator.version().value());
            });
    assertThat(world.store.snapshots)
        .singleElement()
        .satisfies(s -> assertThat(s.reason()).isEqualTo(SnapshotReason.APPLIED));
    assertThat(world.store.findCurrent(candidate.profileId(), job.jobId()).orElseThrow().reason())
        .isEqualTo(ScoreReason.APPLIED);
  }

  // La copia es la que se habría embebido: sin nombre, contacto ni instituciones (ADR-0017,
  // decisión 2).
  @Test
  void theCanonicalCopyHasNoIdentityFields() {
    var candidate = world.candidate("Java");
    var job = world.job("PUBLISHED");
    world.similar(candidate, job, 0.8);

    snapshot(candidate.accountId(), job.jobId(), UUID.randomUUID(), SnapshotReason.APPLIED);

    var text = world.store.applicationSnapshots.get(0).canonicalText();
    assertThat(text).contains("Java").doesNotContain(candidate.accountId().toString());
  }

  @Test
  void aStageChangeStoresOnlyTheScoreSnapshotNotAnotherProfileCopy() {
    var candidate = world.candidate("Java");
    var job = world.job("PUBLISHED");
    world.similar(candidate, job, 0.8);

    var result =
        snapshot(
            candidate.accountId(), job.jobId(), UUID.randomUUID(), SnapshotReason.STAGE_CHANGED);

    assertThat(result.profileSnapshotted()).isFalse();
    assertThat(result.scoreSnapshotted()).isTrue();
    assertThat(world.store.applicationSnapshots).isEmpty();
    assertThat(world.store.snapshots.get(0).reason()).isEqualTo(SnapshotReason.STAGE_CHANGED);
  }

  // Lo que el candidato presentó no puede esperar a un vector: editaría su perfil mientras tanto.
  @Test
  void theProfileIsStoredEvenIfTheScoreIsStillPending() {
    var candidate = world.candidate("Java");
    var job = world.job("PUBLISHED");

    var result =
        snapshot(candidate.accountId(), job.jobId(), UUID.randomUUID(), SnapshotReason.APPLIED);

    assertThat(result.profileSnapshotted()).isTrue();
    assertThat(result.scoreSnapshotted()).isFalse();
    assertThat(world.store.applicationSnapshots).hasSize(1);
    assertThat(world.store.snapshots).isEmpty();
    assertThat(world.metrics).containsExactly("pending");
  }

  @Test
  void repeatingTheCallForTheSameApplicationDoesNotDuplicateTheProfileCopy() {
    var candidate = world.candidate("Java");
    var job = world.job("PUBLISHED");
    world.similar(candidate, job, 0.8);
    var application = UUID.randomUUID();

    snapshot(candidate.accountId(), job.jobId(), application, SnapshotReason.APPLIED);
    snapshot(candidate.accountId(), job.jobId(), application, SnapshotReason.APPLIED);

    assertThat(world.store.applicationSnapshots).hasSize(1);
  }

  @Test
  void anAccountWithoutAReadableProfileCannotBeSnapshotted() {
    var job = world.job("PUBLISHED").jobId();
    var unknown = new AccountId(UUID.randomUUID());
    var application = UUID.randomUUID();

    assertThatThrownBy(() -> snapshot(unknown, job, application, SnapshotReason.APPLIED))
        .isInstanceOf(InvalidStateException.class);

    assertThat(world.store.applicationSnapshots).isEmpty();
    assertThat(world.metrics).containsExactly("no_profile");
  }
}
