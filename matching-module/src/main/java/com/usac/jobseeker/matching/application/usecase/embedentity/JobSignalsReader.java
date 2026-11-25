package com.usac.jobseeker.matching.application.usecase.embedentity;

import com.usac.jobseeker.matching.domain.model.JobPostingId;
import com.usac.jobseeker.matching.domain.model.JobSignals;
import java.util.Optional;

/**
 * Puerto de salida: lo que `matching` lee de una vacante. Lo implementa una capa anticorrupción en
 * `infrastructure.adapter.out.job` sobre la lectura pública de `job`; `matching` nunca lee las
 * tablas de `job` (ARCH-04).
 */
public interface JobSignalsReader {

  /**
   * Las señales actuales de la vacante, o vacío si no hay nada que embeber: no existe, está
   * eliminada o sigue en borrador (un borrador no es candidato a puntuarse, TDD de `job` §5).
   */
  Optional<JobSignals> read(JobPostingId id);
}
