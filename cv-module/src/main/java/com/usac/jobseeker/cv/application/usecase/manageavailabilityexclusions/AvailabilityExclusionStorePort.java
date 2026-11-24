package com.usac.jobseeker.cv.application.usecase.manageavailabilityexclusions;

import com.usac.jobseeker.common.domain.model.AccountId;
import com.usac.jobseeker.cv.domain.model.AvailabilityExclusion;
import com.usac.jobseeker.cv.domain.model.CandidateProfileId;
import java.util.List;

/**
 * Puerto de salida de la lista de exclusión. Lectura y escritura juntas porque son una sola tabla;
 * los nombres no chocan para que un solo adaptador implemente ambos lados.
 */
public interface AvailabilityExclusionStorePort {

  /** Las exclusiones del candidato, de la más reciente a la más antigua. */
  List<AvailabilityExclusion> list(CandidateProfileId profileId);

  int count(CandidateProfileId profileId);

  /** Añade la exclusión; `false` si esa cuenta ya estaba (otro guardado llegó primero). */
  boolean insert(CandidateProfileId profileId, AvailabilityExclusion exclusion);

  /** Quita la exclusión; `false` si esa cuenta no estaba. */
  boolean delete(CandidateProfileId profileId, AccountId excludedAccount);
}
