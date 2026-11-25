package com.usac.jobseeker.job.application.support;

import com.usac.jobseeker.job.domain.model.JobPosting;
import com.usac.jobseeker.job.domain.model.JobPostingId;
import java.util.Optional;

/**
 * Puerto de salida de la vacante. Las consultas por defecto **excluyen** las eliminadas
 * (BR-JOB-09): la fila se conserva por integridad referencial, pero ya no existe para nadie.
 */
public interface JobPostingStorePort {

  /** La vacante, o vacío si no existe o está eliminada. */
  Optional<JobPosting> findById(JobPostingId id);

  void insert(JobPosting posting);

  /**
   * Escribe la vacante completa con su versión nueva solo si la guardada es `expectedVersion`: el
   * *compare-and-set* de toda edición y transición. `false` si otra se adelantó.
   */
  boolean updateIfVersion(JobPosting posting, long expectedVersion);
}
