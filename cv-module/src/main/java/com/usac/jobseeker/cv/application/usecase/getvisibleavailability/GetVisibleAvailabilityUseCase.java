package com.usac.jobseeker.cv.application.usecase.getvisibleavailability;

import com.usac.jobseeker.cv.domain.model.AvailabilityStatus;

/**
 * Consulta interna para otros módulos: el estado de disponibilidad que una cuenta puede ver de un
 * candidato. Concentra en un solo lugar BR-CAND-05 y BR-VIS-06, para que `matching` y `ats` no
 * reimplementen la regla ni puedan olvidarla.
 */
public interface GetVisibleAvailabilityUseCase {

  /**
   * El estado real para el propio candidato; `UNSET` para una cuenta excluida o si el candidato aún
   * no tiene perfil. El resto del perfil no se ve afectado (BR-CAND-06).
   */
  AvailabilityStatus execute(GetVisibleAvailabilityQuery query);
}
