package com.usac.jobseeker.matching.application.usecase.embedentity;

/**
 * Qué se calcula en un trabajo de la cola: un perfil o una vacante. Es el tipo de entidad de
 * `matching_work_queue`, así que las dos comparten cola, trabajador, ventana y reintentos.
 */
public enum EmbeddingEntity {
  PROFILE,
  JOB
}
