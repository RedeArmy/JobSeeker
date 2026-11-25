package com.usac.jobseeker.job.application.support;

import com.usac.jobseeker.job.domain.model.JobPostingId;
import com.usac.jobseeker.job.domain.model.JobRequirement;
import java.util.List;

/** Puerto de salida de los requisitos: hijos del agregado, se leen y reemplazan con él. */
public interface JobRequirementsStorePort {

  /** Los requisitos en el orden en que el reclutador los escribió; vacía si no hay. */
  List<JobRequirement> findByPosting(JobPostingId id);

  /** Reemplaza el conjunto completo; el llamador ya avanzó la versión de la vacante. */
  void replace(JobPostingId id, List<JobRequirement> requirements);
}
