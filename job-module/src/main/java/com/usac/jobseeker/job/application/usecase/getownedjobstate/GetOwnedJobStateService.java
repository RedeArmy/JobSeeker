package com.usac.jobseeker.job.application.usecase.getownedjobstate;

import com.usac.jobseeker.common.application.observability.UseCaseMetricsPort;
import com.usac.jobseeker.job.application.support.JobPostingStorePort;
import com.usac.jobseeker.job.domain.model.JobPostingId;
import java.util.Objects;
import java.util.Optional;
import org.springframework.transaction.annotation.Transactional;

/**
 * Se registra como bean en `infrastructure.config` (ADR-0015, ARCH-02). La consulta del almacén ya
 * excluye las eliminadas (BR-JOB-09) y aquí se descarta la de otro dueño: ambas dan el mismo vacío,
 * así que no se confirma que la vacante exista (TDD de `job` §5, regla 2). La métrica no distingue
 * el motivo ni lleva identificadores (BR-DATA-01).
 */
public class GetOwnedJobStateService implements GetOwnedJobStateUseCase {

  private static final String USE_CASE = "get_owned_job_state";

  private final JobPostingStorePort postings;
  private final UseCaseMetricsPort metrics;

  public GetOwnedJobStateService(JobPostingStorePort postings, UseCaseMetricsPort metrics) {
    this.postings = Objects.requireNonNull(postings, "postings");
    this.metrics = Objects.requireNonNull(metrics, "metrics");
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<OwnedJobState> execute(GetOwnedJobStateQuery query) {
    Optional<OwnedJobState> state =
        postings
            .findById(new JobPostingId(query.jobPostingId()))
            .filter(posting -> posting.isOwnedBy(query.owner()))
            .map(
                posting ->
                    new OwnedJobState(
                        posting.id().value(),
                        posting.status().name(),
                        posting.recruitingStage().name()));
    metrics.count(USE_CASE, state.isPresent() ? "found" : "absent");
    return state;
  }
}
