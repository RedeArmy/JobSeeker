package com.usac.jobseeker.matching.application.usecase.explainscore;

import com.usac.jobseeker.common.domain.model.AccountId;
import com.usac.jobseeker.matching.domain.model.JobPostingId;
import java.util.Objects;

/**
 * El candidato pide ver su compatibilidad con una vacante y por qué. El candidato sale del token.
 */
public record ExplainScoreQuery(AccountId candidate, JobPostingId job) {

  public ExplainScoreQuery {
    Objects.requireNonNull(candidate, "candidate");
    Objects.requireNonNull(job, "job");
  }
}
