package com.usac.jobseeker.matching.infrastructure.adapter.out.job;

import com.usac.jobseeker.common.domain.model.AccountId;
import com.usac.jobseeker.job.application.usecase.getownedjobstate.GetOwnedJobStateQuery;
import com.usac.jobseeker.job.application.usecase.getownedjobstate.GetOwnedJobStateUseCase;
import com.usac.jobseeker.matching.application.usecase.suggestcandidatesforjob.OwnedJobPort;
import com.usac.jobseeker.matching.domain.model.JobPostingId;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Pregunta a `job` si la vacante es del reclutador y en qué estado está, con su caso de uso público
 * (ARCH-04). Una vacante ajena o inexistente da lo mismo: vacío. Se registra a sí mismo como
 * componente por la misma razón que el resto de adaptadores que nombran un tipo de otro módulo.
 */
@Component
public class JobPostingOwnership implements OwnedJobPort {

  private static final String PUBLISHED = "PUBLISHED";
  private static final String REVIEWING = "REVIEWING";

  private final GetOwnedJobStateUseCase state;

  public JobPostingOwnership(GetOwnedJobStateUseCase state) {
    this.state = state;
  }

  // Ante un estado que no conoce, la vacante no se considera publicada: ante la duda no se sugiere.
  @Override
  public Optional<OwnedJob> find(AccountId owner, JobPostingId job) {
    return state
        .execute(new GetOwnedJobStateQuery(owner, job.value()))
        .map(
            s -> new OwnedJob(PUBLISHED.equals(s.status()), REVIEWING.equals(s.recruitingStage())));
  }
}
