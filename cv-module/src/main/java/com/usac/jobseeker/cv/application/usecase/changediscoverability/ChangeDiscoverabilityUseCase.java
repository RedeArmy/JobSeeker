package com.usac.jobseeker.cv.application.usecase.changediscoverability;

import com.usac.jobseeker.common.domain.error.ForbiddenCapabilityException;
import com.usac.jobseeker.common.domain.error.NotFoundException;
import com.usac.jobseeker.common.domain.error.StaleStateException;
import com.usac.jobseeker.cv.application.usecase.getmycandidateprofile.CandidateProfileView;

/**
 * Cambia la descubribilidad del perfil de quien llama y devuelve el perfil completo con su versión
 * nueva. Es idempotente: pedir el valor que ya tiene, con la versión vigente, no cambia nada.
 *
 * <p>Lanza {@link ForbiddenCapabilityException} sin la capacidad de candidato, {@link
 * StaleStateException} si otra edición se adelantó y {@link NotFoundException} si aún no hay perfil
 * de candidato.
 */
public interface ChangeDiscoverabilityUseCase {

  CandidateProfileView execute(ChangeDiscoverabilityCommand command);
}
