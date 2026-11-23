package com.usac.jobseeker.cv.application.usecase.manageexperiences;

import com.usac.jobseeker.common.domain.error.NotFoundException;
import com.usac.jobseeker.common.domain.error.StaleStateException;
import com.usac.jobseeker.common.domain.error.ValidationFailedException;
import com.usac.jobseeker.cv.application.usecase.getmyprofessionalprofile.ProfessionalProfileView;

/**
 * Edita experiencias laborales del perfil profesional de quien llama, elemento a elemento. Cada
 * operación devuelve el perfil completo con su versión nueva.
 *
 * <p>Todas lanzan {@link ValidationFailedException} si un dato no cumple las reglas, {@link
 * StaleStateException} si otra edición se guardó antes y {@link NotFoundException} si el perfil o
 * el elemento no existen o no son de quien llama.
 */
public interface ManageExperiencesUseCase {

  ProfessionalProfileView add(AddExperienceCommand command);

  ProfessionalProfileView update(UpdateExperienceCommand command);

  ProfessionalProfileView remove(RemoveExperienceCommand command);
}
