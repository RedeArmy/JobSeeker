package com.usac.jobseeker.cv.application.usecase.getmyprofessionalprofile;

import com.usac.jobseeker.cv.domain.model.ProfessionalProfileId;
import com.usac.jobseeker.cv.domain.model.ProfileCollections;

/** Puerto de salida de la lectura de las cuatro colecciones de un perfil. */
public interface ProfileCollectionsReadPort {

  /**
   * Las colecciones del perfil, ordenadas de la más reciente a la más antigua. Vacías si no hay.
   */
  ProfileCollections findByProfileId(ProfessionalProfileId profileId);
}
