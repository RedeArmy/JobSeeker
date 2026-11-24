package com.usac.jobseeker.cv.application.usecase.getmycandidateprofile;

import com.usac.jobseeker.common.domain.model.AccountId;
import com.usac.jobseeker.cv.domain.model.CandidateProfile;
import java.util.Optional;

/**
 * Puerto de salida de la lectura: el perfil de candidato de la cuenta, o vacío si aún no lo creó.
 */
public interface CandidateProfileReadPort {

  Optional<CandidateProfile> findByAccountId(AccountId accountId);
}
