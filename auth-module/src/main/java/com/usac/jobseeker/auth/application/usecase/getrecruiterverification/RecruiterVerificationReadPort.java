package com.usac.jobseeker.auth.application.usecase.getrecruiterverification;

import com.usac.jobseeker.common.domain.model.AccountId;
import com.usac.jobseeker.common.domain.model.RecruiterVerification;
import java.util.Optional;

/**
 * Puerto de salida: la verificación de una cuenta activa con la capacidad de reclutador, o vacío si
 * no existe, está en eliminación o no es reclutadora. La condición vive en la consulta para que
 * ningún llamador tenga que repetirla.
 */
public interface RecruiterVerificationReadPort {

  Optional<RecruiterVerification> findOfActiveRecruiter(AccountId accountId);
}
