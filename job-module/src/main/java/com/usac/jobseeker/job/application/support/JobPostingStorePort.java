package com.usac.jobseeker.job.application.support;

import com.usac.jobseeker.common.domain.model.AccountId;
import com.usac.jobseeker.job.domain.model.JobPosting;
import com.usac.jobseeker.job.domain.model.JobPostingId;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Puerto de salida de la vacante. Las consultas por defecto **excluyen** las eliminadas
 * (BR-JOB-09): la fila se conserva por integridad referencial, pero ya no existe para nadie.
 */
public interface JobPostingStorePort {

  /** La vacante, o vacío si no existe o está eliminada. */
  Optional<JobPosting> findById(JobPostingId id);

  /**
   * Las vacantes vivas de entre las pedidas, en una consulta (coding-standards §8.1). Por defecto
   * pregunta de a una; el adaptador real lo sustituye.
   */
  default List<JobPosting> findAllByIds(Collection<JobPostingId> ids) {
    return ids.stream().map(this::findById).flatMap(Optional::stream).toList();
  }

  void insert(JobPosting posting);

  /**
   * Cuántas vacantes `PUBLISHED` tiene la cuenta, tras tomar un candado de transacción propio de
   * esa cuenta: quien publica cuenta y escribe sin que otra publicación de la misma cuenta se cuele
   * en medio (REQ-R-10). El candado se libera al terminar la transacción.
   */
  long lockAndCountPublished(AccountId owner);

  /**
   * Escribe la vacante completa con su versión nueva solo si la guardada es `expectedVersion`: el
   * *compare-and-set* de toda edición y transición. `false` si otra se adelantó.
   */
  boolean updateIfVersion(JobPosting posting, long expectedVersion);

  /**
   * Las vacantes pedidas **incluidas las eliminadas**, en una consulta. Solo para quien debe seguir
   * mostrando una vacante borrada, como el tracker del candidato (BR-JOB-09); el resto del sistema
   * usa las lecturas que las excluyen.
   */
  List<JobPosting> findAllByIdsIncludingDeleted(Collection<JobPostingId> ids);
}
