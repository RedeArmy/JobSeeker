package com.usac.jobseeker.cv.application.usecase.getmycandidateprofile;

import com.usac.jobseeker.common.domain.error.ForbiddenCapabilityException;
import java.util.Optional;

/** Devuelve a quien llama su propio perfil de candidato (`GET /api/v1/me/candidate-profile`). */
public interface GetMyCandidateProfileUseCase {

  /**
   * El perfil, o vacío si la cuenta aún no lo ha creado (no es un error: BR-ACCT-09).
   *
   * @throws ForbiddenCapabilityException si la cuenta no tiene la capacidad de candidato
   */
  Optional<CandidateProfileView> execute(GetMyCandidateProfileQuery query);
}
