package com.usac.jobseeker.ats.infrastructure.adapter.out.job;

import com.usac.jobseeker.ats.application.support.OwnedJobPort;
import com.usac.jobseeker.common.domain.model.AccountId;
import com.usac.jobseeker.job.application.usecase.getownedjobstate.GetOwnedJobStateQuery;
import com.usac.jobseeker.job.application.usecase.getownedjobstate.GetOwnedJobStateUseCase;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Autoriza el pipeline con `getownedjobstate` de `job` (ARCH-04, regla 1 del TDD). Esa lectura ya
 * excluye las eliminadas: el pipeline de una vacante eliminada deja de existir (regla 9).
 */
@Component
public class JobOwnership implements OwnedJobPort {

  private final GetOwnedJobStateUseCase state;

  public JobOwnership(GetOwnedJobStateUseCase state) {
    this.state = state;
  }

  @Override
  public boolean owns(AccountId recruiter, UUID jobPostingId) {
    return state.execute(new GetOwnedJobStateQuery(recruiter, jobPostingId)).isPresent();
  }
}
