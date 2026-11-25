package com.usac.jobseeker.job.application.usecase.getownedjobstate;

import java.util.Optional;

/**
 * Puerto de entrada: el estado de una vacante, solo para su dueño. Es lo que otro módulo usa para
 * comprobar la propiedad sin leer la vacante (BR-VIS-03).
 */
public interface GetOwnedJobStateUseCase {

  /**
   * Vacío si la vacante no existe, está eliminada o **es de otro reclutador**: para quien no es su
   * dueño no existe, y quien llama no puede distinguir los tres casos.
   */
  Optional<OwnedJobState> execute(GetOwnedJobStateQuery query);
}
