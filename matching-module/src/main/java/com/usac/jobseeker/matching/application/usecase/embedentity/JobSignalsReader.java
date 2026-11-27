package com.usac.jobseeker.matching.application.usecase.embedentity;

import com.usac.jobseeker.matching.domain.model.JobPostingId;
import com.usac.jobseeker.matching.domain.model.JobSignals;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
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

  /**
   * Las señales de varias vacantes, con las mismas reglas que {@link #read}: la que no tiene nada
   * que puntuar no figura. Por defecto pregunta de a una; el adaptador real lo hace en una lectura
   * (coding-standards §8.1).
   */
  default Map<JobPostingId, JobSignals> readAll(Collection<JobPostingId> ids) {
    Map<JobPostingId, JobSignals> found = new HashMap<>();
    for (JobPostingId id : ids) {
      read(id).ifPresent(signals -> found.put(id, signals));
    }
    return found;
  }
}
