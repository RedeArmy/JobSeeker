package com.usac.jobseeker.job.application.usecase.managejobrequirements;

import com.usac.jobseeker.common.domain.error.FieldViolation;
import com.usac.jobseeker.common.domain.error.ValidationFailedException;
import com.usac.jobseeker.job.application.support.JobChange;
import com.usac.jobseeker.job.application.support.JobPostingView;
import com.usac.jobseeker.job.application.support.JobRequirementsStorePort;
import com.usac.jobseeker.job.domain.event.JobPostingEvents;
import com.usac.jobseeker.job.domain.model.JobPosting;
import com.usac.jobseeker.job.domain.model.JobRequirement;
import com.usac.jobseeker.job.domain.model.JobRequirements;
import com.usac.jobseeker.job.domain.model.JobStatus;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import org.springframework.transaction.annotation.Transactional;

/**
 * Se registra como bean en `infrastructure.config` (ADR-0015, ARCH-02). La versión de la vacante se
 * avanza **antes** de escribir los requisitos, de modo que si otra edición ya la avanzó, esta falla
 * sin tocar nada. Una vacante ya publicada no puede quedarse sin habilidad ni formación: BR-JOB-02
 * es una condición para publicar y para seguir publicada.
 */
public class ManageJobRequirementsService implements ManageJobRequirementsUseCase {

  private static final String USE_CASE = "manage_job_requirements";

  private final JobRequirementsStorePort requirements;
  private final JobChange change;
  private final Clock clock;

  public ManageJobRequirementsService(
      JobRequirementsStorePort requirements, JobChange change, Clock clock) {
    this.requirements = Objects.requireNonNull(requirements, "requirements");
    this.change = Objects.requireNonNull(change, "change");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  @Override
  @Transactional
  public JobPostingView execute(ManageJobRequirementsCommand command) {
    change.requireRecruiter(USE_CASE, command.owner());
    JobPosting current = change.requireOwned(USE_CASE, command.owner(), command.id());
    change.requireVersion(USE_CASE, current, command.expectedVersion());
    List<JobRequirement> wanted =
        change.guarded(USE_CASE, () -> JobRequirements.of(command.requirements()));
    if (current.status() != JobStatus.DRAFT && !JobRequirements.satisfiesPublication(wanted)) {
      throw change.failed(
          USE_CASE,
          "invalid",
          new ValidationFailedException(
              List.of(new FieldViolation("requirements", "REQUIRED_TO_PUBLISH"))));
    }
    List<JobRequirement> stored = change.requirementsOf(current.id());
    if (wanted.equals(stored)) {
      change.done(USE_CASE, "unchanged");
      return new JobPostingView(current, stored, true);
    }
    JobPosting touched = change.guarded(USE_CASE, () -> current.touched(clock.instant()));
    change.save(USE_CASE, touched, command.expectedVersion());
    requirements.replace(touched.id(), wanted);
    if (touched.status() != JobStatus.DRAFT) {
      change.publish(touched, JobPostingEvents.UPDATED);
    }
    change.done(USE_CASE, "replaced");
    return new JobPostingView(touched, wanted, true);
  }
}
