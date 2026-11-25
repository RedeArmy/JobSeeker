package com.usac.jobseeker.auth.application.usecase.getrecruiterverification;

import com.usac.jobseeker.common.domain.model.RecruiterVerification;
import java.util.Optional;

/**
 * Puerto de entrada: la verificación de una cuenta, para quien tenga que decidir según ella (`job`
 * al publicar, y más adelante `matching` al dar sugerencias).
 */
public interface GetRecruiterVerificationUseCase {

  /** Vacío si la cuenta no existe, no puede actuar o no tiene la capacidad de reclutador. */
  Optional<RecruiterVerification> execute(GetRecruiterVerificationQuery query);
}
