package com.usac.jobseeker.ats.application.support;

import com.usac.jobseeker.common.domain.model.AccountId;
import java.util.Collection;
import java.util.Map;

/**
 * Nombre, titular y contacto de los postulantes, según `cv`. Solo se pide **después** de autorizar
 * al reclutador (REQ-C-07): `ats` no guarda ningún dato personal (regla 7 del TDD).
 */
public interface ApplicantProfilesPort {

  /**
   * @param anonymized la persona pidió ser olvidada: marcador y sin contacto (BR-DATA-05)
   */
  record Profile(
      String fullName,
      String headline,
      String contactEmail,
      String contactPhone,
      boolean anonymized) {}

  /** Los de las cuentas pedidas que existen, en una sola lectura. */
  Map<AccountId, Profile> of(Collection<AccountId> candidates);
}
