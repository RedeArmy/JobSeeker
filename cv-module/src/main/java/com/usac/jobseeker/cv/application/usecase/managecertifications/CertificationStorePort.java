package com.usac.jobseeker.cv.application.usecase.managecertifications;

import com.usac.jobseeker.cv.domain.model.Certification;
import com.usac.jobseeker.cv.domain.model.ProfessionalProfileId;
import com.usac.jobseeker.cv.domain.model.ProfileItemId;

/**
 * Puerto de salida de las certificaciones. Toda operación sobre un elemento lleva el perfil dueño:
 * un identificador de otro perfil no se encuentra, ni se modifica ni se borra (BR-ACCT-04).
 */
public interface CertificationStorePort {

  /** Cuántos elementos tiene el perfil, para respetar el máximo por colección. */
  int count(ProfessionalProfileId profileId);

  void insert(ProfessionalProfileId profileId, Certification item);

  /** Reemplaza el elemento si es de ese perfil; `false` si no existe o es de otro. */
  boolean update(ProfessionalProfileId profileId, Certification item);

  /** Borra el elemento si es de ese perfil; `false` si no existe o es de otro. */
  boolean delete(ProfessionalProfileId profileId, ProfileItemId itemId);
}
