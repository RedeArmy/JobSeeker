package com.usac.jobseeker.cv.application.usecase.getvisibleavailability;

import com.usac.jobseeker.common.domain.model.AccountId;
import com.usac.jobseeker.cv.domain.model.CandidateProfileId;

/** Puerto de salida: ¿esta cuenta está en la lista de exclusión de este candidato? */
public interface AvailabilityExclusionLookupPort {

  boolean isExcluded(CandidateProfileId profileId, AccountId account);

  /**
   * Cuáles de estos candidatos excluyeron a esta cuenta. La implementación por defecto pregunta uno
   * a uno; el adaptador real lo resuelve con una consulta (TD-039).
   */
  default java.util.Set<CandidateProfileId> excludedAmong(
      java.util.Collection<CandidateProfileId> profileIds, AccountId account) {
    java.util.Set<CandidateProfileId> excluded = new java.util.HashSet<>();
    for (CandidateProfileId id : profileIds) {
      if (isExcluded(id, account)) {
        excluded.add(id);
      }
    }
    return excluded;
  }
}
