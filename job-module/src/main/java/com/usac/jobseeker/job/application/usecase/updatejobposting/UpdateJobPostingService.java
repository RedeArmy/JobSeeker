package com.usac.jobseeker.job.application.usecase.updatejobposting;

import com.usac.jobseeker.job.application.support.JobChange;
import com.usac.jobseeker.job.application.support.JobPostingView;
import com.usac.jobseeker.job.domain.event.JobPostingEvents;
import com.usac.jobseeker.job.domain.model.JobDetails;
import com.usac.jobseeker.job.domain.model.JobPosting;
import com.usac.jobseeker.job.domain.model.JobStatus;
import java.time.Clock;
import java.util.Objects;
import org.springframework.transaction.annotation.Transactional;

/**
 * Se registra como bean en `infrastructure.config` (ADR-0015, ARCH-02). Si los datos pedidos son
 * iguales a los guardados no se avanza la versión ni se emite evento: un reenvío no debe provocar
 * recálculos del score (BR-MATCH-03). Un borrador tampoco emite: aún no es candidato a puntuarse.
 */
public class UpdateJobPostingService implements UpdateJobPostingUseCase {

  private static final String USE_CASE = "update_job_posting";

  private final JobChange change;
  private final Clock clock;

  public UpdateJobPostingService(JobChange change, Clock clock) {
    this.change = Objects.requireNonNull(change, "change");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  @Override
  @Transactional
  public JobPostingView execute(UpdateJobPostingCommand command) {
    change.requireRecruiter(USE_CASE, command.owner());
    JobPosting current = change.requireOwned(USE_CASE, command.owner(), command.id());
    change.requireVersion(USE_CASE, current, command.expectedVersion());
    JobDetails details = change.guarded(USE_CASE, command.details()::toDetails);
    if (details.equals(current.details())) {
      change.done(USE_CASE, "unchanged");
      return new JobPostingView(current, change.requirementsOf(current.id()), true);
    }
    JobPosting edited = change.guarded(USE_CASE, () -> current.edit(details, clock.instant()));
    change.save(USE_CASE, edited, command.expectedVersion());
    // BR-MATCH-03: una vacante visible cambió y su vector se recalcula; el borrador no es de nadie.
    if (edited.status() != JobStatus.DRAFT) {
      change.publish(edited, JobPostingEvents.UPDATED);
    }
    change.done(USE_CASE, "updated");
    return new JobPostingView(edited, change.requirementsOf(edited.id()), true);
  }
}
