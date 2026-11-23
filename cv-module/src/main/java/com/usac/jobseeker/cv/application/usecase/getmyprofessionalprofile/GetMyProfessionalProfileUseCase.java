package com.usac.jobseeker.cv.application.usecase.getmyprofessionalprofile;

import java.util.Optional;

/** Devuelve a quien llama su propio perfil profesional (`GET /api/v1/me/professional-profile`). */
public interface GetMyProfessionalProfileUseCase {

  /** El perfil, o vacío si la cuenta aún no lo ha creado (no es un error: BR-ACCT-09). */
  Optional<ProfessionalProfileView> execute(GetMyProfessionalProfileQuery query);
}
