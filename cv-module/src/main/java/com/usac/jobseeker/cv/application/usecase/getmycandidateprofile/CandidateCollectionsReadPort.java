package com.usac.jobseeker.cv.application.usecase.getmycandidateprofile;

import com.usac.jobseeker.cv.domain.model.CandidateCollections;
import com.usac.jobseeker.cv.domain.model.CandidateProfileId;

/** Puerto de salida de la lectura de los tres conjuntos de un perfil de candidato. */
public interface CandidateCollectionsReadPort {

  /** Los conjuntos del perfil; vacíos si no tiene. */
  CandidateCollections findByProfileId(CandidateProfileId profileId);

  /** Varios perfiles con pocas consultas; la implementación por defecto es una a una (TD-039). */
  default java.util.Map<CandidateProfileId, CandidateCollections> findAllByProfileIds(
      java.util.Collection<CandidateProfileId> profileIds) {
    java.util.Map<CandidateProfileId, CandidateCollections> all = new java.util.HashMap<>();
    for (CandidateProfileId id : profileIds) {
      all.put(id, findByProfileId(id));
    }
    return all;
  }
}
