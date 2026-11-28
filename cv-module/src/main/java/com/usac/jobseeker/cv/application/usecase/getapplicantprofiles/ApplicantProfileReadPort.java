package com.usac.jobseeker.cv.application.usecase.getapplicantprofiles;

import com.usac.jobseeker.common.domain.model.AccountId;
import java.util.Collection;
import java.util.List;

/**
 * Puerto de salida: los datos de presentación de varias cuentas en una consulta, **también de las
 * anonimizadas**, a diferencia de las demás lecturas del perfil, que las tratan como ausentes.
 */
public interface ApplicantProfileReadPort {
  List<ApplicantProfile> findApplicants(Collection<AccountId> accounts);
}
