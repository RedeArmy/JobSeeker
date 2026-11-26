package com.usac.jobseeker.cv.application.usecase.getvisibleavailability;

import com.usac.jobseeker.common.domain.model.AccountId;
import java.util.Map;

/**
 * La misma regla que {@link GetVisibleAvailabilityUseCase} (BR-CAND-05, BR-VIS-06) para varios
 * candidatos con pocas consultas (TD-039). Es una interfaz aparte para no obligar a quien simula la
 * individual a cambiar.
 */
public interface GetVisibleAvailabilitiesUseCase {

  /**
   * Por cada cuenta pedida, el nombre del estado (`OPEN_TO_WORK`, `NOT_LOOKING` o `UNSET`) como
   * texto: `UNSET` si el candidato excluyó al reclutador o aún no tiene perfil. Todas las cuentas
   * pedidas aparecen en la respuesta.
   */
  Map<AccountId, String> execute(GetVisibleAvailabilitiesQuery query);
}
