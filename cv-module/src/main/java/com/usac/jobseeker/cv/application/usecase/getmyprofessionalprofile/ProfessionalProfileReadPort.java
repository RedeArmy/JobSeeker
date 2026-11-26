package com.usac.jobseeker.cv.application.usecase.getmyprofessionalprofile;

import com.usac.jobseeker.common.domain.model.AccountId;
import com.usac.jobseeker.cv.domain.model.ProfessionalProfile;
import java.util.Optional;

/** Puerto de salida de la lectura: el perfil de la cuenta, o vacío si aún no lo creó. */
public interface ProfessionalProfileReadPort {

  Optional<ProfessionalProfile> findByAccountId(AccountId accountId);

  /**
   * Varias cuentas con una consulta. La implementación por defecto pregunta una a una, correcta
   * pero lenta: sirve a quien simula el puerto, y el adaptador real la sustituye (TD-039).
   */
  default java.util.Map<AccountId, ProfessionalProfile> findAllByAccountIds(
      java.util.Collection<AccountId> accountIds) {
    java.util.Map<AccountId, ProfessionalProfile> found = new java.util.HashMap<>();
    for (AccountId id : accountIds) {
      findByAccountId(id).ifPresent(profile -> found.put(id, profile));
    }
    return found;
  }
}
