package com.usac.jobseeker.cv.application.usecase.savecandidateprofile;

import com.usac.jobseeker.common.domain.error.ForbiddenCapabilityException;
import com.usac.jobseeker.common.domain.error.StaleStateException;
import com.usac.jobseeker.common.domain.error.ValidationFailedException;

/** Crea o actualiza el perfil de candidato de quien llama (`PUT /api/v1/me/candidate-profile`). */
public interface SaveCandidateProfileUseCase {

  /**
   * @throws ForbiddenCapabilityException si la cuenta no tiene la capacidad de candidato
   * @throws ValidationFailedException si un dato incumple una regla, o si la cuenta aún no tiene
   *     perfil profesional (se crea primero, BR-ACCT-02)
   * @throws StaleStateException si otra edición se guardó antes que la versión que se envía
   */
  SaveCandidateProfileResult execute(SaveCandidateProfileCommand command);
}
