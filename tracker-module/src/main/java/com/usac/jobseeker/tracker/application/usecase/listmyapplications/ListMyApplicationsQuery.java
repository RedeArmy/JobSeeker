package com.usac.jobseeker.tracker.application.usecase.listmyapplications;

import com.usac.jobseeker.common.domain.model.AccountId;
import java.util.Objects;

/** Las postulaciones de quien llama; la cuenta sale del token. */
public record ListMyApplicationsQuery(AccountId candidate) {

  public ListMyApplicationsQuery {
    Objects.requireNonNull(candidate, "candidate");
  }
}
