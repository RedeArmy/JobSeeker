package com.usac.jobseeker.job.application.usecase.createjobposting;

import com.usac.jobseeker.job.application.support.JobChange;
import com.usac.jobseeker.job.application.support.JobPostingStorePort;
import com.usac.jobseeker.job.application.support.JobPostingView;
import com.usac.jobseeker.job.domain.model.JobDetails;
import com.usac.jobseeker.job.domain.model.JobPosting;
import com.usac.jobseeker.job.domain.model.JobPostingId;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.transaction.annotation.Transactional;

/**
 * Se registra como bean en `infrastructure.config` (ADR-0015, ARCH-02). Se comprueba la capacidad
 * antes de validar los datos, para que quien no puede actuar no sepa qué reglas incumpliría
 * (BR-ACCT-04). Un borrador no emite evento: `matching` solo se entera al publicar (§5, regla 6).
 */
public class CreateJobPostingService implements CreateJobPostingUseCase {

  private static final String USE_CASE = "create_job_posting";

  private final JobPostingStorePort postings;
  private final JobChange change;
  private final Clock clock;
  private final Supplier<UUID> ids;

  public CreateJobPostingService(
      JobPostingStorePort postings, JobChange change, Clock clock, Supplier<UUID> ids) {
    this.postings = Objects.requireNonNull(postings, "postings");
    this.change = Objects.requireNonNull(change, "change");
    this.clock = Objects.requireNonNull(clock, "clock");
    this.ids = Objects.requireNonNull(ids, "ids");
  }

  @Override
  @Transactional
  public JobPostingView execute(CreateJobPostingCommand command) {
    change.requireRecruiter(USE_CASE, command.owner());
    JobDetails details = change.guarded(USE_CASE, command.details()::toDetails);
    JobPosting posting =
        JobPosting.create(new JobPostingId(ids.get()), command.owner(), details, clock.instant());
    postings.insert(posting);
    change.done(USE_CASE, "created");
    return new JobPostingView(posting, List.of(), true);
  }
}
