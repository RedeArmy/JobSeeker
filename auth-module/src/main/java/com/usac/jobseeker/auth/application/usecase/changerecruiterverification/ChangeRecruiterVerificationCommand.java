package com.usac.jobseeker.auth.application.usecase.changerecruiterverification;

import com.usac.jobseeker.common.domain.model.AccountId;
import com.usac.jobseeker.common.domain.model.RecruiterVerification;
import java.util.Objects;

/**
 * Cambiar la verificación de una cuenta reclutadora. Lo pide quien verifica (una persona operadora
 * o, más adelante, un flujo de verificación), nunca la propia cuenta: nadie se verifica a sí mismo.
 */
public record ChangeRecruiterVerificationCommand(
    AccountId accountId, RecruiterVerification target) {

  public ChangeRecruiterVerificationCommand {
    Objects.requireNonNull(accountId, "accountId");
    Objects.requireNonNull(target, "target");
  }
}
