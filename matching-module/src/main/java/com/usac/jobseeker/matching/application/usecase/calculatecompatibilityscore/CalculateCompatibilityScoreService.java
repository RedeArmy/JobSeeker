package com.usac.jobseeker.matching.application.usecase.calculatecompatibilityscore;

import com.usac.jobseeker.common.application.observability.UseCaseMetricsPort;
import com.usac.jobseeker.matching.domain.model.CandidateSignals;
import com.usac.jobseeker.matching.domain.model.JobSignals;
import com.usac.jobseeker.matching.domain.model.StoredScore;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.transaction.annotation.Transactional;

/**
 * Se registra como bean en `infrastructure.config` (ADR-0015, ARCH-02). Si falta un vector el par
 * queda `PENDING_EMBEDDING` y **no se puntúa con uno parcial**; si el par ya tenía puntaje, se
 * conserva el anterior hasta que exista el nuevo (se sirve el último con aviso, NFR-AVAIL-02). Ni
 * la métrica ni el registro llevan identificadores (BR-DATA-01).
 */
public class CalculateCompatibilityScoreService implements CalculateCompatibilityScoreUseCase {

  private static final String USE_CASE = "calculate_compatibility_score";

  private final ScoringSources sources;
  private final ScoreCalculator calculator;
  private final UseCaseMetricsPort metrics;
  private final Clock clock;

  public CalculateCompatibilityScoreService(
      ScoringSources sources, ScoreCalculator calculator, UseCaseMetricsPort metrics, Clock clock) {
    this.sources = Objects.requireNonNull(sources, "sources");
    this.calculator = Objects.requireNonNull(calculator, "calculator");
    this.metrics = Objects.requireNonNull(metrics, "metrics");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  @Override
  @Transactional
  public CalculatedScore execute(CalculateCompatibilityScoreCommand command) {
    Optional<CandidateSignals> signals = sources.candidates().read(command.candidate());
    if (signals.isEmpty()) {
      return finish(CalculatedScore.Status.NO_PROFILE);
    }
    Optional<JobSignals> job = sources.jobs().read(command.job());
    if (job.isEmpty()) {
      return finish(CalculatedScore.Status.JOB_UNAVAILABLE);
    }
    Optional<Double> cosine = sources.similarity().cosine(signals.get().profileId(), command.job());
    if (cosine.isEmpty()) {
      return finish(CalculatedScore.Status.PENDING_EMBEDDING);
    }
    StoredScore stored =
        new StoredScore(
            signals.get().profileId(),
            command.job(),
            calculator.score(signals.get(), job.get(), cosine.get()),
            command.reason(),
            clock.instant());
    sources.store().upsertCurrent(List.of(stored));
    metrics.count(USE_CASE, "calculated");
    return new CalculatedScore(CalculatedScore.Status.CALCULATED, stored);
  }

  private CalculatedScore finish(CalculatedScore.Status status) {
    metrics.count(USE_CASE, status.name().toLowerCase(java.util.Locale.ROOT));
    return CalculatedScore.of(status);
  }
}
