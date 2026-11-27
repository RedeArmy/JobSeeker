package com.usac.jobseeker.ats.application.support;

import com.usac.jobseeker.ats.domain.model.ApplicationId;
import com.usac.jobseeker.common.domain.model.AccountId;
import java.util.UUID;

/**
 * Lo que `matching` guarda de una postulación (TD-040, ADR-0017 F6): al postular, la copia del
 * perfil que el candidato presentó y la foto del puntaje. Se llama dentro de la transacción de la
 * postulación: si falla, no queda postulación sin su foto (regla 3 del TDD).
 */
public interface ApplicationSnapshotPort {
  void snapshotOnSubmit(AccountId candidate, UUID jobPostingId, ApplicationId application);
}
