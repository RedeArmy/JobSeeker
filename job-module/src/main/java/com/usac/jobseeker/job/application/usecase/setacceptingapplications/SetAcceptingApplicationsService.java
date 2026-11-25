package com.usac.jobseeker.job.application.usecase.setacceptingapplications;

import com.usac.jobseeker.job.application.support.JobChange;
import com.usac.jobseeker.job.application.support.JobPostingView;
import com.usac.jobseeker.job.domain.model.JobPosting;
import java.time.Clock;
import java.util.Objects;
import org.springframework.transaction.annotation.Transactional;

/**
 * Se registra como bean en `infrastructure.config` (ADR-0015, ARCH-02). No emite evento: pausar las
 * postulaciones no cambia el contenido de la vacante ni su puntuación, y quien postula lo consulta
 * al momento (BR-JOB-10). Pedir el valor que ya tiene no avanza la versión.
 */
public class SetAcceptingApplicationsService implements SetAcceptingApplicationsUseCase {

  private static final String USE_CASE = "set_accepting_applications";

  private final JobChange change;
  private final Clock clock;

  public SetAcceptingApplicationsService(JobChange change, Clock clock) {
    this.change = Objects.requireNonNull(change, "change");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  @Override
  @Transactional
  public JobPostingView execute(SetAcceptingApplicationsCommand command) {
    change.requireRecruiter(USE_CASE, command.owner());
    JobPosting current = change.requireOwned(USE_CASE, command.owner(), command.id());
    change.requireVersion(USE_CASE, current, command.expectedVersion());
    if (current.acceptingApplications() == command.accepting()) {
      change.done(USE_CASE, "unchanged");
      return new JobPostingView(current, change.requirementsOf(current.id()), true);
    }
    JobPosting updated =
        change.guarded(
            USE_CASE,
            () -> current.withAcceptingApplications(command.accepting(), clock.instant()));
    change.save(USE_CASE, updated, command.expectedVersion());
    change.done(USE_CASE, command.accepting() ? "resumed" : "paused");
    return new JobPostingView(updated, change.requirementsOf(updated.id()), true);
  }
}
