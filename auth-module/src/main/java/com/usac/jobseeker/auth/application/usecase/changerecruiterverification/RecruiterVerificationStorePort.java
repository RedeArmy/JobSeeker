package com.usac.jobseeker.auth.application.usecase.changerecruiterverification;

import com.usac.jobseeker.common.domain.model.AccountId;
import com.usac.jobseeker.common.domain.model.RecruiterVerification;
import java.util.Optional;

/** Puerto de salida de la escritura de la verificación. */
public interface RecruiterVerificationStorePort {

  /** La verificación actual de una cuenta con la capacidad de reclutador, o vacío. */
  Optional<RecruiterVerification> findOfRecruiter(AccountId accountId);

  /**
   * Escribe el valor nuevo solo si la cuenta sigue teniendo el anterior: dos operadores que cambian
   * a la vez no se pisan en silencio. `false` si otro se adelantó.
   */
  boolean updateIfCurrent(
      AccountId accountId, RecruiterVerification expected, RecruiterVerification target);
}
