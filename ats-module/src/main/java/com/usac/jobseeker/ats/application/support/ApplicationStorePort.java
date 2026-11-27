package com.usac.jobseeker.ats.application.support;

import com.usac.jobseeker.ats.domain.model.Application;
import com.usac.jobseeker.ats.domain.model.ApplicationId;
import com.usac.jobseeker.ats.domain.model.StatusChange;
import java.util.Optional;
import java.util.UUID;

/** Puerto de salida de las postulaciones y su historial (tablas de `ats`, ADR-0027). */
public interface ApplicationStorePort {

  /**
   * Crea la postulación y su primera fila de historial. Devuelve `false`, sin escribir nada, si el
   * candidato ya tiene una postulación sin retirar a esa vacante: lo decide el índice único de la
   * base, así de dos envíos simultáneos solo uno escribe (BR-TRACK-01, regla 4 del TDD).
   */
  boolean insertIfNoneOpen(Application application, StatusChange change);

  /** Si el candidato ya tiene una postulación sin retirar a la vacante (BR-TRACK-01 y 05). */
  boolean hasOpenApplication(UUID candidateProfileId, UUID jobPostingId);

  Optional<Application> findById(ApplicationId id);
}
