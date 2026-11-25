package com.usac.jobseeker.job.application.usecase.transitionjobposting;

import com.usac.jobseeker.job.application.support.JobChange;
import com.usac.jobseeker.job.application.support.JobPostingView;
import com.usac.jobseeker.job.domain.event.JobPostingEvents;
import com.usac.jobseeker.job.domain.model.Initiator;
import com.usac.jobseeker.job.domain.model.JobPosting;
import com.usac.jobseeker.job.domain.model.JobRequirement;
import com.usac.jobseeker.job.domain.model.JobRequirements;
import com.usac.jobseeker.job.domain.model.JobStatus;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import org.springframework.transaction.annotation.Transactional;

/**
 * Se registra como bean en `infrastructure.config` (ADR-0015, ARCH-02). Toda transición es
 * *compare-and-set* sobre la versión: el primero gana y el otro recibe `STALE_STATE`, que es lo que
 * hará seguras a la vez la acción manual y los lotes de la Ola 3. Aquí el iniciador es siempre el
 * dueño: archivar es del sistema (BR-JOB-14) y por esta puerta no se puede pedir.
 */
public class TransitionJobPostingService implements TransitionJobPostingUseCase {

  private static final String USE_CASE = "transition_job_posting";

  private final JobChange change;
  private final Clock clock;

  public TransitionJobPostingService(JobChange change, Clock clock) {
    this.change = Objects.requireNonNull(change, "change");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  @Override
  @Transactional
  public JobPostingView execute(TransitionJobPostingCommand command) {
    change.requireRecruiter(USE_CASE, command.owner());
    JobPosting current = change.requireOwned(USE_CASE, command.owner(), command.id());
    change.requireVersion(USE_CASE, current, command.expectedVersion());
    List<JobRequirement> requirements = change.requirementsOf(current.id());
    JobPosting moved =
        change.guarded(
            USE_CASE,
            () ->
                current.transition(
                    command.to(),
                    Initiator.OWNER,
                    JobRequirements.satisfiesPublication(requirements),
                    clock.instant()));
    change.save(USE_CASE, moved, command.expectedVersion());
    // Publicar es la puerta del score (§5, regla 6): `matching` solo se entera por estos eventos.
    change.publish(moved, eventFor(current.status(), moved.status()));
    change.done(USE_CASE, moved.status().name().toLowerCase(java.util.Locale.ROOT));
    return new JobPostingView(moved, requirements, true);
  }

  private static String eventFor(JobStatus from, JobStatus to) {
    return switch (to) {
      case PUBLISHED ->
          from == JobStatus.CLOSED ? JobPostingEvents.REOPENED : JobPostingEvents.PUBLISHED;
      case CLOSED -> JobPostingEvents.CLOSED;
      case ARCHIVED -> JobPostingEvents.ARCHIVED;
      case DELETED -> JobPostingEvents.DELETED;
      case DRAFT -> throw new IllegalStateException("ninguna transición vuelve a borrador");
    };
  }
}
