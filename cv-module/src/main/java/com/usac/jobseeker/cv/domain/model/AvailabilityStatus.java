package com.usac.jobseeker.cv.domain.model;

/**
 * Si el candidato busca empleo activamente (BR-CAND-04): una señal de intención, distinta de la
 * compatibilidad, que ordena sugerencias pero nunca altera el puntaje.
 */
public enum AvailabilityStatus {
  OPEN_TO_WORK,
  NOT_LOOKING,
  UNSET
}
