package com.usac.jobseeker.job.application.usecase.getjobposting;

import com.usac.jobseeker.common.application.observability.UseCaseMetricsPort;
import com.usac.jobseeker.common.domain.error.NotFoundException;
import com.usac.jobseeker.job.application.support.CapabilityPort;
import com.usac.jobseeker.job.application.support.JobPostingStorePort;
import com.usac.jobseeker.job.application.support.JobPostingView;
import com.usac.jobseeker.job.application.support.JobRequirementsStorePort;
import com.usac.jobseeker.job.domain.model.JobPosting;
import java.util.Objects;
import org.springframework.transaction.annotation.Transactional;

/**
 * Se registra como bean en `infrastructure.config` (ADR-0015, ARCH-02). El dueño ve su vacante en
 * cualquier estado salvo eliminada. Cualquier otra cuenta necesita la capacidad de candidato y solo
 * ve las visibles (BR-JOB-11): un borrador o una archivada ajena **no existe** para ella, y no se
 * confirma que exista (404, no 403); tampoco se distingue a quien no es candidato. Todo se lee en
 * una transacción de solo lectura.
 */
public class GetJobPostingService implements GetJobPostingUseCase {

  private static final String USE_CASE = "get_job_posting";

  private final JobPostingStorePort postings;
  private final JobRequirementsStorePort requirements;
  private final CapabilityPort capabilities;
  private final UseCaseMetricsPort metrics;

  public GetJobPostingService(
      JobPostingStorePort postings,
      JobRequirementsStorePort requirements,
      CapabilityPort capabilities,
      UseCaseMetricsPort metrics) {
    this.postings = Objects.requireNonNull(postings, "postings");
    this.requirements = Objects.requireNonNull(requirements, "requirements");
    this.capabilities = Objects.requireNonNull(capabilities, "capabilities");
    this.metrics = Objects.requireNonNull(metrics, "metrics");
  }

  @Override
  @Transactional(readOnly = true)
  public JobPostingView execute(GetJobPostingQuery query) {
    JobPosting posting =
        postings
            .findById(query.id())
            .orElseThrow(() -> failed("not_found", new NotFoundException()));
    boolean owner = posting.isOwnedBy(query.viewer());
    // Quien no es el dueño ni candidato recibe lo mismo que ante un identificador inexistente: un
    // 403 aquí confirmaría que la vacante existe (TDD de job §5, regla 2).
    if (!owner
        && (!capabilities.isCandidate(query.viewer())
            || !posting.status().isVisibleToCandidates())) {
      throw failed("not_found", new NotFoundException());
    }
    metrics.count(USE_CASE, owner ? "owner" : "candidate");
    return new JobPostingView(posting, requirements.findByPosting(posting.id()), owner);
  }

  private <E extends RuntimeException> E failed(String outcome, E error) {
    metrics.count(USE_CASE, outcome);
    return error;
  }
}
