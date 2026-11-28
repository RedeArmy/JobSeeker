package com.usac.jobseeker.ats.application.support;

import com.usac.jobseeker.ats.domain.model.Application;
import com.usac.jobseeker.ats.domain.model.ApplicationId;
import com.usac.jobseeker.ats.domain.model.StatusChange;
import com.usac.jobseeker.common.domain.model.AccountId;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Puerto de salida de las postulaciones y su historial (tablas de `ats`, ADR-0027). Las lecturas de
 * varias postulaciones van en una consulta, nunca una por fila (coding-standards §8.1): por eso no
 * hay implementaciones por defecto que pregunten de a una.
 */
public interface ApplicationStorePort {

  /**
   * Crea la postulación y su primera fila de historial. Devuelve `false`, sin escribir nada, si el
   * candidato ya tiene una postulación sin retirar a esa vacante: lo decide el índice único de la
   * base, así de dos envíos simultáneos solo uno escribe (BR-TRACK-01, regla 4 del TDD).
   */
  boolean insertIfNoneOpen(Application application, StatusChange change);

  /**
   * Guarda un movimiento si la versión guardada sigue siendo `expectedVersion`, con su fila de
   * historial; `false`, sin escribir nada, si otro cambio llegó antes (compare-and-set, §3.1).
   */
  boolean updateIfVersion(Application application, StatusChange change, long expectedVersion);

  /** Si el candidato ya tiene una postulación sin retirar a la vacante (BR-TRACK-01 y 05). */
  boolean hasOpenApplication(UUID candidateProfileId, UUID jobPostingId);

  Optional<Application> findById(ApplicationId id);

  /** Las postulaciones de un candidato, de la más reciente a la más antigua. */
  List<Application> findByCandidate(AccountId candidate);

  /**
   * Las postulaciones de una vacante, como mucho `limit`, de la más antigua a la más reciente: el
   * pipeline las ordena después por puntaje (regla 6 del TDD).
   */
  List<Application> findByJob(UUID jobPostingId, int limit);

  /** El historial de cada postulación pedida, en orden, con una sola consulta. */
  Map<ApplicationId, List<StatusChange>> historyOf(Collection<ApplicationId> ids);
}
