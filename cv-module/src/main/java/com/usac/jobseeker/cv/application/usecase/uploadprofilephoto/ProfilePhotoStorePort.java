package com.usac.jobseeker.cv.application.usecase.uploadprofilephoto;

import com.usac.jobseeker.common.domain.model.AccountId;
import com.usac.jobseeker.common.domain.rules.FileType;
import com.usac.jobseeker.cv.domain.model.ProfessionalProfileId;
import java.util.Optional;

/**
 * Puerto de salida de la foto del perfil profesional (`tdd/cv-module.md` §10.8). La foto no cambia
 * la versión del perfil: no la lee `matching` ni ningún otro módulo, y editarla no debe obligar a
 * recargar el formulario del perfil.
 */
public interface ProfilePhotoStorePort {

  /**
   * El hueco de la foto de un perfil vivo; sin foto, {@code key} y {@code type} son nulos.
   *
   * @param version la versión del perfil, para asociar el evento de borrado a su agregado
   */
  record PhotoSlot(ProfessionalProfileId profileId, long version, String key, FileType type) {
    public boolean hasPhoto() {
      return key != null;
    }
  }

  /** El hueco de la foto del perfil de la cuenta, si tiene perfil y no está anonimizado. */
  Optional<PhotoSlot> find(AccountId account);

  /** Lo mismo, bloqueando el perfil hasta el final de la transacción (`FOR UPDATE`). */
  Optional<PhotoSlot> lock(AccountId account);

  /** Fija la foto del perfil; {@code key} y {@code type} nulos la quitan. */
  void set(ProfessionalProfileId profile, String key, FileType type);
}
