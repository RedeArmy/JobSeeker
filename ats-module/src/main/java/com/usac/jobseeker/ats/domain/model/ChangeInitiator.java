package com.usac.jobseeker.ats.domain.model;

/** Quién provoca un cambio de la postulación; queda en el historial (BR-TRACK-02, REQ-R-03). */
public enum ChangeInitiator {
  /** El candidato dueño de la postulación. */
  CANDIDATE,
  /** El reclutador dueño de la vacante. */
  RECRUITER,
  /** Un proceso automático; en la Ola 1 no hay ninguno que cambie una postulación. */
  SYSTEM
}
