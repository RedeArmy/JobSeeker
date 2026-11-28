package com.usac.jobseeker.cv.application.usecase.getapplicantprofiles;

import com.usac.jobseeker.common.domain.model.AccountId;
import java.util.List;
import java.util.Objects;

/**
 * De qué postulantes. Interna, para `ats`, que solo la llama **después** de comprobar que quien
 * pregunta es el dueño de la vacante a la que postularon (REQ-C-07, TDD de `ats` §5).
 */
public record GetApplicantProfilesQuery(List<AccountId> accounts) {

  public GetApplicantProfilesQuery {
    accounts = List.copyOf(Objects.requireNonNull(accounts, "accounts"));
  }
}
