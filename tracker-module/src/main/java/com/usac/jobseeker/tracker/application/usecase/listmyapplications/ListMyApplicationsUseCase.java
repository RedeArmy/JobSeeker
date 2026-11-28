package com.usac.jobseeker.tracker.application.usecase.listmyapplications;

import java.util.List;

/**
 * Mis postulaciones, de la más reciente a la más antigua. Lanza {@code
 * ForbiddenCapabilityException} sin la capacidad de candidato.
 */
public interface ListMyApplicationsUseCase {
  List<MyApplication> execute(ListMyApplicationsQuery query);
}
