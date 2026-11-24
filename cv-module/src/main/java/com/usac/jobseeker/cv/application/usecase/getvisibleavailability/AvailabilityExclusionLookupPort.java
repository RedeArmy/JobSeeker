package com.usac.jobseeker.cv.application.usecase.getvisibleavailability;

import com.usac.jobseeker.common.domain.model.AccountId;
import com.usac.jobseeker.cv.domain.model.CandidateProfileId;

/** Puerto de salida: ¿esta cuenta está en la lista de exclusión de este candidato? */
public interface AvailabilityExclusionLookupPort {

  boolean isExcluded(CandidateProfileId profileId, AccountId account);
}
