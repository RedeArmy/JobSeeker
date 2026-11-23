package com.usac.jobseeker.cv.application.usecase.saveprofessionalprofile;

import com.usac.jobseeker.common.domain.error.StaleStateException;
import com.usac.jobseeker.common.domain.error.ValidationFailedException;

/**
 * Crea o actualiza el perfil profesional de quien llama (`PUT /api/v1/me/professional-profile`).
 */
public interface SaveProfessionalProfileUseCase {

  /**
   * @throws ValidationFailedException si algún dato incumple una regla del dominio
   * @throws StaleStateException si otra edición se guardó antes que la versión que se envía
   */
  SaveProfessionalProfileResult execute(SaveProfessionalProfileCommand command);
}
