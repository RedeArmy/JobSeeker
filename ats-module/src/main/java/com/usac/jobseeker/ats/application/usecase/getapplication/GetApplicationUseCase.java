package com.usac.jobseeker.ats.application.usecase.getapplication;

/**
 * El detalle de una postulación para el reclutador dueño de la vacante. Lanza {@code
 * ForbiddenCapabilityException} sin la capacidad de reclutador y {@code NotFoundException} si la
 * postulación no existe o su vacante es de otro, o está eliminada: no se distingue (reglas 1 y 2).
 */
public interface GetApplicationUseCase {
  ApplicationDetail execute(GetApplicationQuery query);
}
