package com.usac.jobseeker.cv.application.usecase.manageprojects;

import com.usac.jobseeker.cv.domain.model.ProfessionalProfileId;
import com.usac.jobseeker.cv.domain.model.ProfileItemId;
import com.usac.jobseeker.cv.domain.model.Project;

/**
 * Puerto de salida de los proyectos. Toda operación sobre un elemento lleva el perfil dueño: un
 * identificador de otro perfil no se encuentra, ni se modifica ni se borra (BR-ACCT-04).
 */
public interface ProjectStorePort {

  /** Cuántos elementos tiene el perfil, para respetar el máximo por colección. */
  int count(ProfessionalProfileId profileId);

  void insert(ProfessionalProfileId profileId, Project item);

  /** Reemplaza el elemento si es de ese perfil; `false` si no existe o es de otro. */
  boolean update(ProfessionalProfileId profileId, Project item);

  /** Borra el elemento si es de ese perfil; `false` si no existe o es de otro. */
  boolean delete(ProfessionalProfileId profileId, ProfileItemId itemId);
}
