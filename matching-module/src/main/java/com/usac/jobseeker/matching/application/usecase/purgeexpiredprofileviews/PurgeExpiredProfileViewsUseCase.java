package com.usac.jobseeker.matching.application.usecase.purgeexpiredprofileviews;

/**
 * Borra del registro de perfiles vistos lo que ya pasó su plazo de retención (TD-035). Lo dispara
 * un lote diario; nunca toca lo que la cuota de los sugeridos aún cuenta.
 */
public interface PurgeExpiredProfileViewsUseCase {

  /** Cuántas filas se borraron en esta pasada. */
  int execute();
}
