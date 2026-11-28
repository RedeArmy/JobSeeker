package com.usac.jobseeker.ats.application.usecase.listjobapplications;

/**
 * El pipeline de una vacante propia. Lanza {@code ForbiddenCapabilityException} sin la capacidad de
 * reclutador y {@code NotFoundException} si la vacante no existe, está eliminada o es de otro: no
 * se distingue (regla 1 del TDD).
 */
public interface ListJobApplicationsUseCase {
  PipelinePage execute(ListJobApplicationsQuery query);
}
