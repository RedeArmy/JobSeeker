package com.usac.jobseeker.matching.domain.model;

/** Por qué se guarda una foto del puntaje (ADR-0017, decisión 9). */
public enum SnapshotReason {
  /** Al postular: lo que el reclutador evalúa es lo que el candidato presentó. */
  APPLIED,
  /** Al cambiar de etapa en el pipeline. */
  STAGE_CHANGED,
  /**
   * Al cambiar `algorithm_version`: la foto es la del puntaje **anterior**, para poder comparar.
   */
  VERSION_CHANGED,
  /** Muestras periódicas para estudiar la deriva; se encienden solo si el arnés las pide. */
  SAMPLE
}
