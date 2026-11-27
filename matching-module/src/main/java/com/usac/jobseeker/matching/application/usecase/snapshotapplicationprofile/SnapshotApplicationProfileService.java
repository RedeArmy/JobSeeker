package com.usac.jobseeker.matching.application.usecase.snapshotapplicationprofile;

import com.usac.jobseeker.common.application.observability.UseCaseMetricsPort;
import com.usac.jobseeker.common.domain.error.InvalidStateException;
import com.usac.jobseeker.matching.application.usecase.calculatecompatibilityscore.CalculateCompatibilityScoreCommand;
import com.usac.jobseeker.matching.application.usecase.calculatecompatibilityscore.CalculateCompatibilityScoreUseCase;
import com.usac.jobseeker.matching.application.usecase.calculatecompatibilityscore.CalculatedScore;
import com.usac.jobseeker.matching.application.usecase.calculatecompatibilityscore.ScoreCalculator;
import com.usac.jobseeker.matching.application.usecase.calculatecompatibilityscore.ScoringSources;
import com.usac.jobseeker.matching.domain.model.ApplicationProfileSnapshot;
import com.usac.jobseeker.matching.domain.model.CandidateSignals;
import com.usac.jobseeker.matching.domain.model.JobPostingId;
import com.usac.jobseeker.matching.domain.model.ScoreReason;
import com.usac.jobseeker.matching.domain.model.ScoreSnapshot;
import com.usac.jobseeker.matching.domain.model.SnapshotReason;
import com.usac.jobseeker.matching.domain.service.CanonicalProfileComposer;
import java.time.Clock;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;

/**
 * Se registra como bean en `infrastructure.config` (ADR-0015, ARCH-02). El perfil canónico se
 * guarda **siempre** que se postula, aunque el puntaje esté pendiente: lo que el candidato presentó
 * no puede esperar a un vector, porque editaría el perfil mientras tanto. La foto del puntaje se
 * toma cuando se puede. Nada de la copia sale en el registro ni en la métrica (BR-DATA-01).
 */
public class SnapshotApplicationProfileService implements SnapshotApplicationProfileUseCase {

  private static final String USE_CASE = "snapshot_application_profile";

  private final ScoringSources sources;
  private final CalculateCompatibilityScoreUseCase calculate;
  private final ScoreCalculator calculator;
  private final int maxCharacters;
  private final UseCaseMetricsPort metrics;
  private final Clock clock;

  public SnapshotApplicationProfileService(
      ScoringSources sources,
      CalculateCompatibilityScoreUseCase calculate,
      ScoreCalculator calculator,
      int maxCharacters,
      UseCaseMetricsPort metrics,
      Clock clock) {
    this.sources = Objects.requireNonNull(sources, "sources");
    this.calculate = Objects.requireNonNull(calculate, "calculate");
    this.calculator = Objects.requireNonNull(calculator, "calculator");
    this.maxCharacters = maxCharacters;
    this.metrics = Objects.requireNonNull(metrics, "metrics");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  @Override
  @Transactional
  public SnapshotResult execute(SnapshotApplicationProfileCommand command) {
    Optional<CandidateSignals> signals = sources.candidates().read(command.candidate());
    if (signals.isEmpty()) {
      metrics.count(USE_CASE, "no_profile");
      throw new InvalidStateException();
    }
    JobPostingId job = new JobPostingId(command.jobPostingId());
    SnapshotReason reason = reasonOf(command.reason());
    boolean profile = false;
    if (reason == SnapshotReason.APPLIED) {
      var composed = CanonicalProfileComposer.compose(signals.get(), maxCharacters);
      sources
          .store()
          .insertApplicationSnapshot(
              new ApplicationProfileSnapshot(
                  UUID.randomUUID(),
                  signals.get().profileId(),
                  job,
                  command.applicationId(),
                  composed.text().value(),
                  composed.text().contentHash(),
                  calculator.version().value(),
                  clock.instant()));
      profile = true;
    }
    CalculatedScore calculated =
        calculate.execute(
            new CalculateCompatibilityScoreCommand(command.candidate(), job, ScoreReason.APPLIED));
    boolean score = calculated.status() == CalculatedScore.Status.CALCULATED;
    if (score) {
      sources
          .store()
          .insertSnapshot(
              new ScoreSnapshot(
                  UUID.randomUUID(),
                  signals.get().profileId(),
                  job,
                  reason,
                  calculated.score().score(),
                  clock.instant()));
    }
    metrics.count(USE_CASE, score ? "snapshotted" : "pending");
    return new SnapshotResult(profile, score);
  }

  private static SnapshotReason reasonOf(SnapshotApplicationProfileCommand.Reason reason) {
    return switch (reason) {
      case APPLIED -> SnapshotReason.APPLIED;
      case STAGE_CHANGED -> SnapshotReason.STAGE_CHANGED;
    };
  }
}
