package com.usac.jobseeker.job.infrastructure.adapter.out.auth;

import com.usac.jobseeker.auth.application.usecase.getrecruiterverification.GetRecruiterVerificationQuery;
import com.usac.jobseeker.auth.application.usecase.getrecruiterverification.GetRecruiterVerificationUseCase;
import com.usac.jobseeker.common.domain.model.AccountId;
import com.usac.jobseeker.common.domain.model.RecruiterVerification;
import com.usac.jobseeker.job.application.support.RecruiterStandingPort;
import org.springframework.stereotype.Component;

/**
 * Pregunta a `auth` por la verificación de la cuenta con su caso de uso público: `job` nunca lee la
 * tabla `accounts` (ARCH-04). Una cuenta que `auth` no reconoce como reclutadora activa se trata
 * como suspendida: ante la duda no se publica.
 *
 * <p>Se registra a sí mismo como componente porque es el único lugar donde `job` puede nombrar un
 * tipo de `auth`: la configuración (`infrastructure.config`) solo ve el puerto propio de `job`.
 */
@Component
public class AuthRecruiterStanding implements RecruiterStandingPort {

  private final GetRecruiterVerificationUseCase verification;

  public AuthRecruiterStanding(GetRecruiterVerificationUseCase verification) {
    this.verification = verification;
  }

  @Override
  public RecruiterVerification standingOf(AccountId accountId) {
    return verification
        .execute(new GetRecruiterVerificationQuery(accountId))
        .orElse(RecruiterVerification.SUSPENDED);
  }
}
