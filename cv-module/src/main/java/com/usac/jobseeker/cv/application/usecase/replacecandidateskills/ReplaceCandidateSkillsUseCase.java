package com.usac.jobseeker.cv.application.usecase.replacecandidateskills;

import com.usac.jobseeker.common.domain.error.ForbiddenCapabilityException;
import com.usac.jobseeker.common.domain.error.NotFoundException;
import com.usac.jobseeker.common.domain.error.StaleStateException;
import com.usac.jobseeker.common.domain.error.ValidationFailedException;
import com.usac.jobseeker.cv.application.usecase.getmycandidateprofile.CandidateProfileView;

/**
 * Reemplaza entero el conjunto de habilidades del perfil de candidato de quien llama y devuelve el
 * perfil completo con su versión nueva. Es idempotente: reenviar el mismo conjunto con la versión
 * vigente no cambia nada.
 *
 * <p>Lanza {@link ForbiddenCapabilityException} sin la capacidad de candidato, {@link
 * ValidationFailedException} si un dato no cumple las reglas, {@link StaleStateException} si otra
 * edición se guardó antes y {@link NotFoundException} si aún no hay perfil de candidato.
 */
public interface ReplaceCandidateSkillsUseCase {

  CandidateProfileView execute(ReplaceCandidateSkillsCommand command);
}
