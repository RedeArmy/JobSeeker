package com.usac.jobseeker.matching.application.usecase.calculatecompatibilityscore;

import com.usac.jobseeker.matching.domain.model.CandidateProfileId;
import com.usac.jobseeker.matching.domain.model.JobPostingId;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;

/**
 * Puerto de salida: la similitud coseno **cruda** entre el vector de un perfil y el de una vacante,
 * calculada sobre los vectores ya guardados y **solo si son del mismo modelo** (ADR-0018). Vacía si
 * falta alguno de los dos vectores: el par queda `PENDING_EMBEDDING` y nunca se puntúa con uno
 * parcial (ADR-0017, decisión 1).
 */
public interface PairSimilarityPort {

  Optional<Double> cosine(CandidateProfileId profile, JobPostingId job);

  /** Los perfiles pedidos contra una vacante, con una consulta; faltan los que no tienen vector. */
  Map<CandidateProfileId, Double> cosinesForJob(
      JobPostingId job, Collection<CandidateProfileId> profiles);

  /** Un perfil contra las vacantes pedidas, con una consulta; faltan las que no tienen vector. */
  Map<JobPostingId, Double> cosinesForProfile(
      CandidateProfileId profile, Collection<JobPostingId> jobs);
}
