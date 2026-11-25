package com.usac.jobseeker.job.application.usecase.setrecruitingstage;

import com.usac.jobseeker.job.application.support.JobChange;
import com.usac.jobseeker.job.application.support.JobPostingView;
import com.usac.jobseeker.job.domain.event.JobPostingEvents;
import com.usac.jobseeker.job.domain.model.JobPosting;
import java.time.Clock;
import java.util.Locale;
import java.util.Objects;
import org.springframework.transaction.annotation.Transactional;

/**
 * Se registra como bean en `infrastructure.config` (ADR-0015, ARCH-02). Emite evento porque
 * `REVIEWING` saca la vacante de la generación de sugeridos (BR-MATCH-08) y `matching` debe
 * enterarse sin esperar a la siguiente consulta. Pedir la etapa que ya tiene no avanza la versión.
 */
public class SetRecruitingStageService implements SetRecruitingStageUseCase {

  private static final String USE_CASE = "set_recruiting_stage";

  private final JobChange change;
  private final Clock clock;

  public SetRecruitingStageService(JobChange change, Clock clock) {
    this.change = Objects.requireNonNull(change, "change");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  @Override
  @Transactional
  public JobPostingView execute(SetRecruitingStageCommand command) {
    change.requireRecruiter(USE_CASE, command.owner());
    JobPosting current = change.requireOwned(USE_CASE, command.owner(), command.id());
    change.requireVersion(USE_CASE, current, command.expectedVersion());
    if (current.recruitingStage() == command.stage()) {
      change.done(USE_CASE, "unchanged");
      return new JobPostingView(current, change.requirementsOf(current.id()), true);
    }
    JobPosting updated =
        change.guarded(
            USE_CASE, () -> current.withRecruitingStage(command.stage(), clock.instant()));
    change.save(USE_CASE, updated, command.expectedVersion());
    change.publish(updated, JobPostingEvents.RECRUITING_STAGE_CHANGED);
    change.done(USE_CASE, command.stage().name().toLowerCase(Locale.ROOT));
    return new JobPostingView(updated, change.requirementsOf(updated.id()), true);
  }
}
