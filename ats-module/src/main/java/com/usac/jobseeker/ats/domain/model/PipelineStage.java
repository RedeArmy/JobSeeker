package com.usac.jobseeker.ats.domain.model;

/**
 * La columna pública del tablero kanban (D1, BR-TRACK-03): lo que el candidato ve. Es un catálogo
 * fijo y común a todos los reclutadores en la Ola 1 (D2); los estados internos de un reclutador, si
 * llegan en la Ola 2, vivirán dentro de una de estas columnas y nunca la reemplazarán.
 */
public enum PipelineStage {
  NEW,
  REVIEW,
  INTERVIEW,
  OFFER
}
