package com.usac.jobseeker.matching.domain.model;

/**
 * Por qué existe el puntaje vigente de un par (ADR-0017, decisión 9: solo los pares «con motivo»).
 * Cuanto más fuerte el motivo, más importa mantenerlo al día y conservarlo: un par postulado nunca
 * baja de motivo aunque luego se vuelva a ver como sugerido.
 */
public enum ScoreReason {
  /** Un reclutador lo vio entre los sugeridos de su vacante. */
  SUGGESTED,
  /** El candidato consultó su compatibilidad con la vacante. */
  CANDIDATE_VIEW,
  /** El candidato postuló. */
  APPLIED
}
