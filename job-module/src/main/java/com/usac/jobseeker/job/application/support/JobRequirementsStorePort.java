package com.usac.jobseeker.job.application.support;

import com.usac.jobseeker.job.domain.model.JobPostingId;
import com.usac.jobseeker.job.domain.model.JobRequirement;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Puerto de salida de los requisitos: hijos del agregado, se leen y reemplazan con él. */
public interface JobRequirementsStorePort {

  /** Los requisitos en el orden en que el reclutador los escribió; vacía si no hay. */
  List<JobRequirement> findByPosting(JobPostingId id);

  /**
   * Los requisitos de varias vacantes, en una consulta; una vacante sin requisitos no figura. Por
   * defecto pregunta de a una; el adaptador real lo sustituye (coding-standards §8.1).
   */
  default Map<JobPostingId, List<JobRequirement>> findByPostings(Collection<JobPostingId> ids) {
    Map<JobPostingId, List<JobRequirement>> found = new HashMap<>();
    for (JobPostingId id : ids) {
      List<JobRequirement> items = findByPosting(id);
      if (!items.isEmpty()) {
        found.put(id, items);
      }
    }
    return found;
  }

  /** Reemplaza el conjunto completo; el llamador ya avanzó la versión de la vacante. */
  void replace(JobPostingId id, List<JobRequirement> requirements);
}
