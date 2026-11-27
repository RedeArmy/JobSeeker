package com.usac.jobseeker.ats.application.usecase.submitapplication;

import com.usac.jobseeker.common.domain.model.AccountId;
import java.util.UUID;

/** Lo que `job` dice sobre postular a una vacante; `ats` nunca lee `job_postings`. */
public interface JobApplicationGatePort {

  /** La respuesta de `job`, con un tipo propio de `ats` (ARCH-04). */
  enum Gate {
    OPEN,
    NOT_FOUND,
    NOT_ACCEPTING,
    OWN_JOB
  }

  Gate check(AccountId applicant, UUID jobPostingId);
}
