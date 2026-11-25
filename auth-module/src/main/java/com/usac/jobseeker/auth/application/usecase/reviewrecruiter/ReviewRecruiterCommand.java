package com.usac.jobseeker.auth.application.usecase.reviewrecruiter;

import com.usac.jobseeker.common.domain.model.AccountId;
import com.usac.jobseeker.common.domain.model.RecruiterVerification;
import java.util.Objects;

/**
 * La decisión de un operador sobre la verificación de un reclutador. El operador sale del token,
 * nunca del cuerpo ni de la ruta (BR-ACCT-04).
 */
public record ReviewRecruiterCommand(
    AccountId operator, AccountId target, RecruiterVerification decision) {

  public ReviewRecruiterCommand {
    Objects.requireNonNull(operator, "operator");
    Objects.requireNonNull(target, "target");
    Objects.requireNonNull(decision, "decision");
  }
}
