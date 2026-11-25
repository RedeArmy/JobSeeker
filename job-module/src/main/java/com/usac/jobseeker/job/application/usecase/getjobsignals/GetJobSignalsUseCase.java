package com.usac.jobseeker.job.application.usecase.getjobsignals;

import java.util.Optional;

/**
 * Puerto de entrada de la superficie de lectura de `job` para `matching` (TDD de job §4.1). Es la
 * **única** puerta por la que el contenido de una vacante sale del módulo.
 */
public interface GetJobSignalsUseCase {

  /** La proyección, o vacío si la vacante no existe, es un borrador o está eliminada. */
  Optional<JobSnapshot> execute(GetJobSignalsQuery query);
}
