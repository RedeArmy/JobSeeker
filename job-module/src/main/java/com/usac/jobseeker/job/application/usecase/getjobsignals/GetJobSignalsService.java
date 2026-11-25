package com.usac.jobseeker.job.application.usecase.getjobsignals;

import com.usac.jobseeker.common.application.observability.UseCaseMetricsPort;
import com.usac.jobseeker.job.application.support.JobPostingStorePort;
import com.usac.jobseeker.job.application.support.JobRequirementsStorePort;
import com.usac.jobseeker.job.domain.model.JobPosting;
import com.usac.jobseeker.job.domain.model.JobPostingId;
import com.usac.jobseeker.job.domain.model.JobRequirement;
import com.usac.jobseeker.job.domain.model.JobStatus;
import com.usac.jobseeker.job.domain.model.SalaryRange;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.transaction.annotation.Transactional;

/**
 * Se registra como bean en `infrastructure.config` (ADR-0015, ARCH-02). Es la **única** puerta por
 * la que el contenido de una vacante sale de `job`: lo que no se copia aquí no puede llegar a
 * ningún consumidor, por eso cada campo se copia a mano y no se reutiliza ninguna vista, que podría
 * crecer con un dato del reclutador sin que nadie lo note. Un borrador no se puntúa (TDD §5, regla
 * 6) y una eliminada ya no existe (BR-JOB-09); el resto se entrega con su estado para que
 * `matching` decida qué hacer con cada uno.
 */
public class GetJobSignalsService implements GetJobSignalsUseCase {

  private static final String USE_CASE = "get_job_signals";

  private final JobPostingStorePort postings;
  private final JobRequirementsStorePort requirements;
  private final UseCaseMetricsPort metrics;

  public GetJobSignalsService(
      JobPostingStorePort postings,
      JobRequirementsStorePort requirements,
      UseCaseMetricsPort metrics) {
    this.postings = Objects.requireNonNull(postings, "postings");
    this.requirements = Objects.requireNonNull(requirements, "requirements");
    this.metrics = Objects.requireNonNull(metrics, "metrics");
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<JobSnapshot> execute(GetJobSignalsQuery query) {
    Optional<JobSnapshot> snapshot =
        postings
            .findById(new JobPostingId(query.jobPostingId()))
            .filter(posting -> posting.status() != JobStatus.DRAFT)
            .map(posting -> project(posting, requirements.findByPosting(posting.id())));
    metrics.count(USE_CASE, snapshot.isPresent() ? "found" : "unavailable");
    return snapshot;
  }

  private static JobSnapshot project(JobPosting posting, List<JobRequirement> items) {
    SalaryRange range = posting.details().salary();
    return new JobSnapshot(
        posting.id().value(),
        posting.version(),
        posting.status().name(),
        posting.recruitingStage().name(),
        posting.acceptingApplications(),
        posting.details().title(),
        posting.details().description(),
        posting.details().modality() == null ? null : posting.details().modality().name(),
        posting.details().country(),
        range == null
            ? null
            : new JobSnapshot.Salary(
                range.min(), range.max(), range.currency(), range.period().name()),
        items.stream().map(GetJobSignalsService::requirement).toList());
  }

  private static JobSnapshot.Requirement requirement(JobRequirement r) {
    return new JobSnapshot.Requirement(
        r.type().name(),
        r.mandatory(),
        r.skillName(),
        r.minLevel(),
        r.educationLevel() == null ? null : r.educationLevel().name(),
        r.minYears(),
        r.language(),
        r.minProficiency() == null ? null : r.minProficiency().name());
  }
}
