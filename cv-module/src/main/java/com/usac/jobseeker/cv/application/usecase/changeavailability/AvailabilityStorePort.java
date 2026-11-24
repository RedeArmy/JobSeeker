package com.usac.jobseeker.cv.application.usecase.changeavailability;

import com.usac.jobseeker.cv.domain.model.CandidateProfile;

/** Puerto de salida de la escritura de la disponibilidad, condicional a la versión esperada. */
public interface AvailabilityStorePort {

  /** Escribe `availability_status` y la versión nueva; `false` si otra edición se adelantó. */
  boolean updateAvailabilityIfVersion(CandidateProfile profile, long expectedVersion);
}
