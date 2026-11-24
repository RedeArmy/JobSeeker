package com.usac.jobseeker.cv.application.usecase.getmycandidateprofile;

import com.usac.jobseeker.cv.domain.model.CandidateCollections;
import com.usac.jobseeker.cv.domain.model.CandidateProfileId;

/** Puerto de salida de la lectura de los tres conjuntos de un perfil de candidato. */
public interface CandidateCollectionsReadPort {

  /** Los conjuntos del perfil; vacíos si no tiene. */
  CandidateCollections findByProfileId(CandidateProfileId profileId);
}
