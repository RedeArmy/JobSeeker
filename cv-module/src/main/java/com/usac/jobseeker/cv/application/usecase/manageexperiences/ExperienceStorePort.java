package com.usac.jobseeker.cv.application.usecase.manageexperiences;

import com.usac.jobseeker.cv.domain.model.Experience;
import com.usac.jobseeker.cv.domain.model.ProfessionalProfileId;
import com.usac.jobseeker.cv.domain.model.ProfileItemId;

/**
 * Puerto de salida de las experiencias. Toda operación sobre un elemento lleva el perfil dueño: un
 * identificador de otro perfil no se encuentra, ni se modifica ni se borra (BR-ACCT-04).
 */
public interface ExperienceStorePort {

  /** Cuántos elementos tiene el perfil, para respetar el máximo por colección. */
  int count(ProfessionalProfileId profileId);

  void insert(ProfessionalProfileId profileId, Experience item);

  /** Reemplaza el elemento si es de ese perfil; `false` si no existe o es de otro. */
  boolean update(ProfessionalProfileId profileId, Experience item);

  /** Borra el elemento si es de ese perfil; `false` si no existe o es de otro. */
  boolean delete(ProfessionalProfileId profileId, ProfileItemId itemId);
}
