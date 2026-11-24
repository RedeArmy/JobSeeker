package com.usac.jobseeker.cv.application.usecase.getmycandidateprofile;

import com.usac.jobseeker.common.domain.model.AccountId;
import java.util.Objects;

/** Quién pregunta: siempre la propia cuenta, nunca un identificador que llegue de fuera. */
public record GetMyCandidateProfileQuery(AccountId accountId) {

  public GetMyCandidateProfileQuery {
    Objects.requireNonNull(accountId, "accountId");
  }
}
