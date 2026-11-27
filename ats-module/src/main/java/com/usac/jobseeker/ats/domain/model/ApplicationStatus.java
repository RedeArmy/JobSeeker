package com.usac.jobseeker.ats.domain.model;

/**
 * Si la postulación sigue viva (tdd/ats-tracker-modules.md §3.1). Es un eje distinto de la etapa:
 * una descartada conserva la etapa a la que había llegado, y por eso reabrirla la devuelve allí
 * (D3).
 */
public enum ApplicationStatus {
  /** En proceso. */
  ACTIVE,
  /** Contratado. Terminal: no se reabre (D3). */
  HIRED,
  /** Descartado por el reclutador. Terminal para el candidato; el reclutador puede reabrirla. */
  REJECTED,
  /** Retirada por el candidato (REQ-C-03). Terminal; el candidato puede volver a postular. */
  WITHDRAWN
}
