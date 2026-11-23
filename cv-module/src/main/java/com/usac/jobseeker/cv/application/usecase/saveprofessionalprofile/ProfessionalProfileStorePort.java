package com.usac.jobseeker.cv.application.usecase.saveprofessionalprofile;

import com.usac.jobseeker.common.domain.model.AccountId;
import com.usac.jobseeker.cv.domain.model.ProfessionalProfile;
import java.util.Optional;

/**
 * Puerto de salida de la escritura. Las dos operaciones son condicionales y devuelven si
 * escribieron: así dos guardados simultáneos no se pisan (bloqueo optimista) y el perdedor lo sabe
 * sin depender de una excepción de la base de datos.
 */
public interface ProfessionalProfileStorePort {

  Optional<ProfessionalProfile> findByAccountId(AccountId accountId);

  /** Crea el perfil; `false` si la cuenta ya tenía uno (otro guardado llegó primero). */
  boolean insertIfAbsent(ProfessionalProfile profile);

  /**
   * Reemplaza el perfil si su versión guardada sigue siendo `expectedVersion`; `false` si cambió.
   * `profile` ya trae la versión nueva.
   */
  boolean updateIfVersion(ProfessionalProfile profile, long expectedVersion);
}
