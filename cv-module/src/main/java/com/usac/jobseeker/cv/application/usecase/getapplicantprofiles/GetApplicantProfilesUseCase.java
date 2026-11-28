package com.usac.jobseeker.cv.application.usecase.getapplicantprofiles;

import java.util.List;

/**
 * Por lotes, nombre, titular y contacto de los postulantes, para el reclutador de su postulación
 * (REQ-C-07). Una cuenta sin perfil profesional no aparece. Interna: la consume `ats`.
 */
public interface GetApplicantProfilesUseCase {
  List<ApplicantProfile> execute(GetApplicantProfilesQuery query);
}
