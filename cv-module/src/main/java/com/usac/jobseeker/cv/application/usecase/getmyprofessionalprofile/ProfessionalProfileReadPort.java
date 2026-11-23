package com.usac.jobseeker.cv.application.usecase.getmyprofessionalprofile;

import com.usac.jobseeker.common.domain.model.AccountId;
import com.usac.jobseeker.cv.domain.model.ProfessionalProfile;
import java.util.Optional;

/** Puerto de salida de la lectura: el perfil de la cuenta, o vacío si aún no lo creó. */
public interface ProfessionalProfileReadPort {

  Optional<ProfessionalProfile> findByAccountId(AccountId accountId);
}
