package com.usac.jobseeker.matching.application.usecase.suggestcandidatesforjob;

import com.usac.jobseeker.common.domain.model.AccountId;
import com.usac.jobseeker.common.domain.model.RecruiterVerification;
import java.util.Optional;

/**
 * Puerto de salida: la verificación de la cuenta reclutadora. La verificación es de `auth`
 * (BR-ACCT-11); `matching` solo la pregunta, y la decisión de qué hacer con ella es suya.
 */
public interface RecruiterTrustPort {

  /** Vacío si la cuenta no existe, no puede actuar o no tiene la capacidad de reclutador. */
  Optional<RecruiterVerification> verificationOf(AccountId account);
}
