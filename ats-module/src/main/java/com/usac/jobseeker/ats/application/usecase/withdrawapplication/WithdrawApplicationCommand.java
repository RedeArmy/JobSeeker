package com.usac.jobseeker.ats.application.usecase.withdrawapplication;

import com.usac.jobseeker.common.domain.model.AccountId;
import java.util.Objects;
import java.util.UUID;

/** Retirar una postulación propia (REQ-C-03). El candidato sale del token, nunca del cuerpo. */
public record WithdrawApplicationCommand(AccountId candidate, UUID applicationId) {

  public WithdrawApplicationCommand {
    Objects.requireNonNull(candidate, "candidate");
    Objects.requireNonNull(applicationId, "applicationId");
  }
}
