package com.usac.jobseeker.matching.application.usecase.suggestcandidatesforjob;

import com.usac.jobseeker.common.domain.model.AccountId;
import com.usac.jobseeker.matching.domain.model.JobPostingId;
import java.util.Optional;

/**
 * Puerto de salida: ¿esta vacante es de esta cuenta y en qué estado está? La propiedad es de `job`
 * y se pregunta sin leer la vacante (BR-VIS-03).
 */
public interface OwnedJobPort {

  /** El estado que importa a los sugeridos. */
  record OwnedJob(boolean published, boolean reviewing) {}

  /** Vacío si no existe, está eliminada o es de otro reclutador: quien pregunta no lo distingue. */
  Optional<OwnedJob> find(AccountId owner, JobPostingId job);
}
