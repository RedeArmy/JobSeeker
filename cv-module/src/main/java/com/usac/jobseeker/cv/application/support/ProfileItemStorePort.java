package com.usac.jobseeker.cv.application.support;

import com.usac.jobseeker.cv.domain.model.ProfessionalProfileId;
import com.usac.jobseeker.cv.domain.model.ProfileItemId;

/**
 * Lo común a los almacenes de las cuatro colecciones del perfil (TD-013): cada puerto de colección
 * lo extiende con su tipo de elemento, así el flujo de añadir, reemplazar y quitar se escribe una
 * sola vez en {@link ProfileItemChanges}.
 *
 * @param <T> el elemento de la colección (experiencia, formación, certificación o proyecto)
 */
public interface ProfileItemStorePort<T> {

  /** Cuántos elementos tiene el perfil, para respetar el máximo por colección. */
  int count(ProfessionalProfileId profileId);

  void insert(ProfessionalProfileId profileId, T item);

  /** Reemplaza el elemento si es de ese perfil; `false` si no existe o es de otro. */
  boolean update(ProfessionalProfileId profileId, T item);

  /** Borra el elemento si es de ese perfil; `false` si no existe o es de otro. */
  boolean delete(ProfessionalProfileId profileId, ProfileItemId itemId);
}
