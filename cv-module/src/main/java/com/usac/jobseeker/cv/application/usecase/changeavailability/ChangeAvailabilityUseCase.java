package com.usac.jobseeker.cv.application.usecase.changeavailability;

import com.usac.jobseeker.common.domain.error.ForbiddenCapabilityException;
import com.usac.jobseeker.common.domain.error.NotFoundException;
import com.usac.jobseeker.common.domain.error.StaleStateException;
import com.usac.jobseeker.cv.application.usecase.getmycandidateprofile.CandidateProfileView;

/**
 * Declara el estado de disponibilidad del perfil de quien llama y devuelve el perfil completo.
 * Idempotente: declarar el estado que ya tiene, con la versión vigente, no cambia nada.
 *
 * <p>Lanza {@link ForbiddenCapabilityException} sin la capacidad de candidato, {@link
 * StaleStateException} si otra edición se adelantó y {@link NotFoundException} si aún no hay perfil
 * de candidato.
 */
public interface ChangeAvailabilityUseCase {

  CandidateProfileView execute(ChangeAvailabilityCommand command);
}
