package com.usac.jobseeker.cv.application.usecase.changediscoverability;

import com.usac.jobseeker.cv.domain.model.CandidateProfile;

/** Puerto de salida de la escritura de la descubribilidad, condicional a la versión esperada. */
public interface DiscoverabilityStorePort {

  /** Escribe `discoverable` y la versión nueva; `false` si otra edición se adelantó. */
  boolean updateDiscoverabilityIfVersion(CandidateProfile profile, long expectedVersion);
}
