package com.usac.jobseeker.matching.application.usecase.suggestcandidatesforjob;

import com.usac.jobseeker.common.domain.model.AccountId;
import com.usac.jobseeker.matching.domain.model.JobPostingId;
import java.util.Objects;

/**
 * Pedir los candidatos sugeridos de una vacante propia. El reclutador sale del token (BR-ACCT-04).
 */
public record SuggestCandidatesForJobQuery(AccountId recruiter, JobPostingId job, int size) {

  public SuggestCandidatesForJobQuery {
    Objects.requireNonNull(recruiter, "recruiter");
    Objects.requireNonNull(job, "job");
    if (size <= 0) {
      throw new IllegalArgumentException("el tamaño de la página debe ser positivo");
    }
  }
}
