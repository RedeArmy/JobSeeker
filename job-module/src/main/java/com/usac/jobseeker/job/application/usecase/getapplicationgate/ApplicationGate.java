package com.usac.jobseeker.job.application.usecase.getapplicationgate;

/** Lo que `job` responde sobre postular a una vacante (tdd/ats-tracker-modules.md §5). */
public enum ApplicationGate {
  /** Acepta postulaciones. */
  OPEN,
  /** No existe, está eliminada o un candidato no puede verla (BR-JOB-11): no se distingue. */
  NOT_FOUND,
  /** Visible, pero cerrada o con las postulaciones en pausa (BR-JOB-10). */
  NOT_ACCEPTING,
  /** Es del propio reclutador que quiere postular (BR-ACCT-05). */
  OWN_JOB
}
