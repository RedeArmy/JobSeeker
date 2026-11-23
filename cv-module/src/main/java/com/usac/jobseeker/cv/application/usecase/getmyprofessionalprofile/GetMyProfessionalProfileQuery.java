package com.usac.jobseeker.cv.application.usecase.getmyprofessionalprofile;

import com.usac.jobseeker.common.domain.model.AccountId;
import java.util.Objects;

/** Quién pregunta: siempre la propia cuenta, nunca un identificador que llegue de fuera. */
public record GetMyProfessionalProfileQuery(AccountId accountId) {

  public GetMyProfessionalProfileQuery {
    Objects.requireNonNull(accountId, "accountId");
  }
}
