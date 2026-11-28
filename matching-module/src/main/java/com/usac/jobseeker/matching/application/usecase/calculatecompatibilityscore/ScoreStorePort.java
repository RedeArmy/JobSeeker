package com.usac.jobseeker.matching.application.usecase.calculatecompatibilityscore;

import com.usac.jobseeker.matching.domain.model.ApplicationProfileSnapshot;
import com.usac.jobseeker.matching.domain.model.CandidateProfileId;
import com.usac.jobseeker.matching.domain.model.JobPostingId;
import com.usac.jobseeker.matching.domain.model.ScoreSnapshot;
import com.usac.jobseeker.matching.domain.model.StoredScore;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Puerto de salida: dónde viven el puntaje vigente de cada par con motivo, sus fotos y la copia del
 * perfil canónico al postular (ADR-0017, decisión 9). Se usa dentro de la transacción del caso de
 * uso.
 */
public interface ScoreStorePort {

  /**
   * Guarda el puntaje vigente de cada par, reemplazando el anterior. El motivo más fuerte se
   * conserva: un par `APPLIED` no baja a `SUGGESTED` por volver a verse como sugerido.
   */
  void upsertCurrent(Collection<StoredScore> scores);

  Optional<StoredScore> findCurrent(CandidateProfileId profile, JobPostingId job);

  /** Las vacantes con puntaje vigente de este perfil, las más recientes primero y hasta `limit`. */
  List<JobPostingId> jobsOfProfile(CandidateProfileId profile, int limit);

  /**
   * Los perfiles con puntaje vigente en esta vacante, los más recientes primero y hasta `limit`.
   */
  List<CandidateProfileId> profilesOfJob(JobPostingId job, int limit);

  /**
   * Las vacantes a las que el perfil postuló (las que tienen copia del perfil al postular), hasta
   * `limit`. El recálculo las recorre aunque no tengan puntaje vigente: si al postular aún no había
   * vector, el puntaje se crea cuando llega, en vez de quedar pendiente para siempre.
   */
  List<JobPostingId> appliedJobsOfProfile(CandidateProfileId profile, int limit);

  /** Los perfiles que postularon a la vacante, hasta `limit`; por la misma razón. */
  List<CandidateProfileId> appliedProfilesOfJob(JobPostingId job, int limit);

  /** Los puntajes vigentes de estos perfiles en la vacante, en una sola consulta. */
  Map<CandidateProfileId, StoredScore> findCurrentForJob(
      JobPostingId job, Collection<CandidateProfileId> profiles);

  /**
   * El puntaje de cada perfil **al postular** (la foto `APPLIED` más reciente), en una sola
   * consulta. Falta si al postular el puntaje estaba pendiente.
   */
  Map<CandidateProfileId, Double> appliedOverallForJob(
      JobPostingId job, Collection<CandidateProfileId> profiles);

  /**
   * Hasta `limit` puntajes vigentes calculados con **otra** versión del algoritmo, los más viejos
   * primero. Son los que `migratealgorithmversion` debe recalcular.
   */
  List<StoredScore> staleVersion(String currentVersion, int limit);

  /**
   * Elimina el vigente de un par que ya no se puede calcular (vacante eliminada, perfil sin
   * acceso).
   */
  void deleteCurrent(CandidateProfileId profile, JobPostingId job);

  /**
   * Mueve un par al final de la cola de migración sin cambiar su puntaje ni su versión: para el que
   * no se pudo recalcular ahora (falta un vector, la vacante no se lee) y no debe volver a ocupar
   * el primer lugar en cada pasada.
   */
  void touch(CandidateProfileId profile, JobPostingId job, Instant at);

  void insertSnapshot(ScoreSnapshot snapshot);

  void insertApplicationSnapshot(ApplicationProfileSnapshot snapshot);

  /**
   * Anonimiza todo lo derivado de este perfil (BR-DATA-05): el desglose y las explicaciones se
   * vacían y el texto canónico copiado al postular se borra. Quedan el par, el puntaje y la
   * versión, porque el reclutador que ya evaluó a esa persona conserva su registro (BR-DATA-05).
   */
  void anonymizeProfile(CandidateProfileId profile, Instant at);

  /** Borra las fotos anteriores a `before` (retención) y devuelve cuántas. */
  int purgeSnapshotsOlderThan(Instant before);
}
