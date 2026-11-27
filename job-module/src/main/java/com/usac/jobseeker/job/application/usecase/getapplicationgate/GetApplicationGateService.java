package com.usac.jobseeker.job.application.usecase.getapplicationgate;

import com.usac.jobseeker.common.application.observability.UseCaseMetricsPort;
import com.usac.jobseeker.job.application.support.JobPostingStorePort;
import com.usac.jobseeker.job.domain.model.JobPosting;
import com.usac.jobseeker.job.domain.model.JobPostingId;
import com.usac.jobseeker.job.domain.model.JobStatus;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import org.springframework.transaction.annotation.Transactional;

/**
 * Se registra como bean en `infrastructure.config` (ADR-0015, ARCH-02). `getjobposting` no basta:
 * al candidato le da un contrato sin dueño ni pausa, y `getownedjobstate` solo responde al dueño.
 */
public class GetApplicationGateService implements GetApplicationGateUseCase {

  private static final String USE_CASE = "get_application_gate";

  private final JobPostingStorePort postings;
  private final UseCaseMetricsPort metrics;

  public GetApplicationGateService(JobPostingStorePort postings, UseCaseMetricsPort metrics) {
    this.postings = Objects.requireNonNull(postings, "postings");
    this.metrics = Objects.requireNonNull(metrics, "metrics");
  }

  @Override
  @Transactional(readOnly = true)
  public ApplicationGate execute(GetApplicationGateQuery query) {
    ApplicationGate gate = decide(postings.findById(new JobPostingId(query.jobPostingId())), query);
    metrics.count(USE_CASE, gate.name().toLowerCase(Locale.ROOT));
    return gate;
  }

  // El orden importa. La propia va primero: su dueño la ve en cualquier estado, y BR-ACCT-05 es el
  // motivo real por el que no puede postular. Para cualquier otro, una vacante que no puede ver no
  // existe (BR-JOB-11), antes de decir si acepta postulaciones: así no se confirma que exista.
  private static ApplicationGate decide(Optional<JobPosting> found, GetApplicationGateQuery query) {
    if (found.isEmpty()) {
      return ApplicationGate.NOT_FOUND;
    }
    JobPosting posting = found.get();
    if (posting.isOwnedBy(query.applicant())) {
      return ApplicationGate.OWN_JOB;
    }
    if (!posting.status().isVisibleToCandidates()) {
      return ApplicationGate.NOT_FOUND;
    }
    // Una cerrada sigue visible pero no recibe postulaciones; la pausa solo cuenta mientras está
    // publicada (BR-JOB-10).
    boolean accepting = posting.status() == JobStatus.PUBLISHED && posting.acceptingApplications();
    return accepting ? ApplicationGate.OPEN : ApplicationGate.NOT_ACCEPTING;
  }
}
