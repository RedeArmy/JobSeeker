package com.usac.jobseeker.matching.application.usecase.explainscore;

import com.usac.jobseeker.common.application.observability.UseCaseMetricsPort;
import com.usac.jobseeker.common.domain.error.InvalidStateException;
import com.usac.jobseeker.matching.application.usecase.calculatecompatibilityscore.ScoreCalculator;
import com.usac.jobseeker.matching.application.usecase.calculatecompatibilityscore.ScoringSources;
import com.usac.jobseeker.matching.domain.model.CandidateSignals;
import com.usac.jobseeker.matching.domain.model.JobSignals;
import java.util.Objects;
import java.util.Optional;
import org.springframework.transaction.annotation.Transactional;

/**
 * Se registra como bean en `infrastructure.config` (ADR-0015, ARCH-02). La visibilidad es la misma
 * que la del puntaje real: lo hipotético no abre una vía para ver una vacante que no se ve.
 */
public class WhatIfScoreService implements WhatIfScoreUseCase {

  private static final String USE_CASE = "what_if_score";

  private final CandidateJobAccess access;
  private final ScoringSources sources;
  private final ScoreCalculator calculator;
  private final UseCaseMetricsPort metrics;

  public WhatIfScoreService(
      CandidateJobAccess access,
      ScoringSources sources,
      ScoreCalculator calculator,
      UseCaseMetricsPort metrics) {
    this.access = Objects.requireNonNull(access, "access");
    this.sources = Objects.requireNonNull(sources, "sources");
    this.calculator = Objects.requireNonNull(calculator, "calculator");
    this.metrics = Objects.requireNonNull(metrics, "metrics");
  }

  @Override
  @Transactional(readOnly = true)
  public WhatIfScoreResult execute(WhatIfScoreQuery query) {
    JobSignals job = access.requireVisible(query.candidate(), query.job());
    CandidateSignals signals =
        sources.candidates().read(query.candidate()).orElseThrow(InvalidStateException::new);
    Optional<Double> cosine = sources.similarity().cosine(signals.profileId(), query.job());
    if (cosine.isEmpty()) {
      metrics.count(USE_CASE, "pending_embedding");
      return new WhatIfScoreResult(WhatIfScoreResult.Status.PENDING_EMBEDDING, null, null);
    }
    CandidateSignals hypothetical = signals.withAdditional(query.skills(), query.languages());
    metrics.count(USE_CASE, "ok");
    return new WhatIfScoreResult(
        WhatIfScoreResult.Status.OK,
        calculator.score(signals, job, cosine.get()),
        calculator.score(hypothetical, job, cosine.get()));
  }
}
