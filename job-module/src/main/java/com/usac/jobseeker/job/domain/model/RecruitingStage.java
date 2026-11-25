package com.usac.jobseeker.job.domain.model;

/**
 * Etapa de reclutamiento de una vacante publicada (BR-JOB-04). `REVIEWING` pausa la sugerencia
 * proactiva de candidatos (BR-MATCH-08) sin cerrar la vacante ni pausar las postulaciones.
 */
public enum RecruitingStage {
  HIRING,
  REVIEWING
}
