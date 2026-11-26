package com.usac.jobseeker.cv.application.usecase.getmyprofessionalprofile;

import com.usac.jobseeker.cv.domain.model.ProfessionalProfileId;
import com.usac.jobseeker.cv.domain.model.ProfileCollections;

/** Puerto de salida de la lectura de las cuatro colecciones de un perfil. */
public interface ProfileCollectionsReadPort {

  /**
   * Las colecciones del perfil, ordenadas de la más reciente a la más antigua. Vacías si no hay.
   */
  ProfileCollections findByProfileId(ProfessionalProfileId profileId);

  /** Varios perfiles con pocas consultas; la implementación por defecto es una a una (TD-039). */
  default java.util.Map<ProfessionalProfileId, ProfileCollections> findAllByProfileIds(
      java.util.Collection<ProfessionalProfileId> profileIds) {
    java.util.Map<ProfessionalProfileId, ProfileCollections> all = new java.util.HashMap<>();
    for (ProfessionalProfileId id : profileIds) {
      all.put(id, findByProfileId(id));
    }
    return all;
  }
}
