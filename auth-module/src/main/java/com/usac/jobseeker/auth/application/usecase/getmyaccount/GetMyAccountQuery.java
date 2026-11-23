package com.usac.jobseeker.auth.application.usecase.getmyaccount;

import com.usac.jobseeker.common.domain.model.AccountId;
import java.util.Objects;

/** Quién pregunta por su cuenta: siempre la propia, nunca un identificador que llegue de fuera. */
public record GetMyAccountQuery(AccountId accountId) {

  public GetMyAccountQuery {
    Objects.requireNonNull(accountId, "accountId");
  }
}
