package com.usac.jobseeker.ats.application.usecase.listcandidateapplications;

import com.usac.jobseeker.common.domain.model.AccountId;
import java.util.Objects;

/**
 * De qué candidato. Es una consulta interna para `tracker`, que ya comprobó que quien pregunta es
 * ese candidato: `ats` no vuelve a autorizar.
 */
public record ListCandidateApplicationsQuery(AccountId candidate) {

  public ListCandidateApplicationsQuery {
    Objects.requireNonNull(candidate, "candidate");
  }
}
