package com.usac.jobseeker.matching.application.usecase.embedentity;

/** Los eventos de `job` que cambian el embedding de una vacante o su visibilidad. */
public enum JobEventKind {
  /** El contenido cambió: hay que recalcular, agrupando ediciones. */
  CONTENT_UPDATED,
  /**
   * Cambió su estado (publicada, reabierta, cerrada, archivada, eliminada) o su etapa de
   * reclutamiento: debe notarse enseguida, porque decide si es candidata a puntuarse o a sugerirse.
   */
  STATE_CHANGED
}
