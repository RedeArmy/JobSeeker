package com.usac.jobseeker.tracker.application.usecase.getmyapplication;

import com.usac.jobseeker.tracker.application.usecase.listmyapplications.MyApplication;

/**
 * El detalle de una postulación propia con su historial completo (BR-TRACK-02). Lanza {@code
 * ForbiddenCapabilityException} sin la capacidad de candidato y {@code NotFoundException} si no
 * existe o es de otro: no se distingue (TDD §4.2).
 */
public interface GetMyApplicationUseCase {
  MyApplication execute(GetMyApplicationQuery query);
}
