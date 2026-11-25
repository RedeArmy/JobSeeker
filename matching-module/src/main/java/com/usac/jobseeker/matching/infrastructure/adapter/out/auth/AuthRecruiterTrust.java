package com.usac.jobseeker.matching.infrastructure.adapter.out.auth;

import com.usac.jobseeker.auth.application.usecase.getrecruiterverification.GetRecruiterVerificationQuery;
import com.usac.jobseeker.auth.application.usecase.getrecruiterverification.GetRecruiterVerificationUseCase;
import com.usac.jobseeker.common.domain.model.AccountId;
import com.usac.jobseeker.common.domain.model.RecruiterVerification;
import com.usac.jobseeker.matching.application.usecase.suggestcandidatesforjob.RecruiterTrustPort;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Pregunta a `auth` por la verificación del reclutador con su caso de uso público: `matching` nunca
 * lee la tabla `accounts` (ARCH-04). Se registra a sí mismo como componente porque es el único
 * lugar donde `matching` nombra un tipo de `auth`.
 */
@Component
public class AuthRecruiterTrust implements RecruiterTrustPort {

  private final GetRecruiterVerificationUseCase verification;

  public AuthRecruiterTrust(GetRecruiterVerificationUseCase verification) {
    this.verification = verification;
  }

  @Override
  public Optional<RecruiterVerification> verificationOf(AccountId account) {
    return verification.execute(new GetRecruiterVerificationQuery(account));
  }
}
