package com.usac.jobseeker.ats.infrastructure.adapter.out.job;

import com.usac.jobseeker.ats.application.usecase.submitapplication.JobApplicationGatePort;
import com.usac.jobseeker.common.domain.model.AccountId;
import com.usac.jobseeker.job.application.usecase.getapplicationgate.GetApplicationGateQuery;
import com.usac.jobseeker.job.application.usecase.getapplicationgate.GetApplicationGateUseCase;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Pregunta a `job` si se puede postular, con su caso de uso público (ARCH-04): `ats` nunca lee
 * `job_postings`. Traduce la respuesta al tipo propio de `ats`, así un valor nuevo en `job` obliga
 * a decidir aquí qué significa en vez de colarse.
 */
@Component
public class JobApplicationGate implements JobApplicationGatePort {

  private final GetApplicationGateUseCase gate;

  public JobApplicationGate(GetApplicationGateUseCase gate) {
    this.gate = gate;
  }

  @Override
  public Gate check(AccountId applicant, UUID jobPostingId) {
    return switch (gate.execute(new GetApplicationGateQuery(applicant, jobPostingId))) {
      case OPEN -> Gate.OPEN;
      case NOT_FOUND -> Gate.NOT_FOUND;
      case NOT_ACCEPTING -> Gate.NOT_ACCEPTING;
      case OWN_JOB -> Gate.OWN_JOB;
    };
  }
}
