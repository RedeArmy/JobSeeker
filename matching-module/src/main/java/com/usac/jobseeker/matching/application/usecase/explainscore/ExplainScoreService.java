package com.usac.jobseeker.matching.application.usecase.explainscore;

import com.usac.jobseeker.common.application.observability.UseCaseMetricsPort;
import com.usac.jobseeker.common.domain.error.InvalidStateException;
import com.usac.jobseeker.matching.application.usecase.calculatecompatibilityscore.CalculateCompatibilityScoreCommand;
import com.usac.jobseeker.matching.application.usecase.calculatecompatibilityscore.CalculateCompatibilityScoreUseCase;
import com.usac.jobseeker.matching.application.usecase.calculatecompatibilityscore.CalculatedScore;
import com.usac.jobseeker.matching.application.usecase.embedentity.EmbeddingEntity;
import com.usac.jobseeker.matching.application.usecase.embedentity.EmbeddingWorkQueuePort;
import com.usac.jobseeker.matching.domain.model.ScoreReason;
import java.util.Objects;

/**
 * Se registra como bean en `infrastructure.config` (ADR-0015, ARCH-02). El puntaje se calcula **al
 * pedirlo** sobre lo que hay hoy (es aritmética sobre vectores y señales ya guardados), de modo que
 * nunca es uno viejo; `updating` avisa si hay un cambio en cola que todavía no se refleja en el
 * vector. Sin datos personales en la métrica (BR-DATA-01).
 */
public class ExplainScoreService implements ExplainScoreUseCase {

  private static final String USE_CASE = "explain_score";

  private final CandidateJobAccess access;
  private final CalculateCompatibilityScoreUseCase calculate;
  private final EmbeddingWorkQueuePort queue;
  private final UseCaseMetricsPort metrics;

  public ExplainScoreService(
      CandidateJobAccess access,
      CalculateCompatibilityScoreUseCase calculate,
      EmbeddingWorkQueuePort queue,
      UseCaseMetricsPort metrics) {
    this.access = Objects.requireNonNull(access, "access");
    this.calculate = Objects.requireNonNull(calculate, "calculate");
    this.queue = Objects.requireNonNull(queue, "queue");
    this.metrics = Objects.requireNonNull(metrics, "metrics");
  }

  @Override
  public ExplainScoreResult execute(ExplainScoreQuery query) {
    access.requireVisible(query.candidate(), query.job());
    CalculatedScore calculated =
        calculate.execute(
            new CalculateCompatibilityScoreCommand(
                query.candidate(), query.job(), ScoreReason.CANDIDATE_VIEW));
    return switch (calculated.status()) {
      case CALCULATED -> {
        metrics.count(USE_CASE, "ok");
        yield new ExplainScoreResult(
            ExplainScoreResult.Status.OK, calculated.score().score(), updating(query));
      }
      case PENDING_EMBEDDING -> {
        metrics.count(USE_CASE, "pending_embedding");
        yield new ExplainScoreResult(ExplainScoreResult.Status.PENDING_EMBEDDING, null, true);
      }
      // Sin perfil legible no hay nada que puntuar; la vacante ya se comprobó visible arriba.
      case NO_PROFILE, JOB_UNAVAILABLE -> {
        metrics.count(USE_CASE, "unavailable");
        throw new InvalidStateException();
      }
    };
  }

  private boolean updating(ExplainScoreQuery query) {
    boolean profile = queue.isPending(EmbeddingEntity.PROFILE, query.candidate().value());
    return profile || queue.isPending(EmbeddingEntity.JOB, query.job().value());
  }
}
