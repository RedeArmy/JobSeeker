package com.usac.jobseeker.cv.application.usecase.getmycandidateprofile;

import com.usac.jobseeker.common.domain.model.AccountId;
import com.usac.jobseeker.cv.domain.model.CandidateProfile;
import java.util.Optional;

/**
 * Puerto de salida de la lectura: el perfil de candidato de la cuenta, o vacío si aún no lo creó.
 */
public interface CandidateProfileReadPort {

  Optional<CandidateProfile> findByAccountId(AccountId accountId);

  /**
   * Varias cuentas con una consulta; ver {@code ProfessionalProfileReadPort.findAllByAccountIds}.
   */
  default java.util.Map<AccountId, CandidateProfile> findAllByAccountIds(
      java.util.Collection<AccountId> accountIds) {
    java.util.Map<AccountId, CandidateProfile> found = new java.util.HashMap<>();
    for (AccountId id : accountIds) {
      findByAccountId(id).ifPresent(profile -> found.put(id, profile));
    }
    return found;
  }
}
