package com.usac.jobseeker.matching.domain.model;

/**
 * El estado de disponibilidad de un candidato tal como lo ve quien consulta (BR-CAND-04). Lo
 * determina `cv`, que ya aplica la lista de exclusión (BR-CAND-05, BR-VIS-06): para una cuenta
 * excluida llega como `UNSET`. Aquí solo ordena; nunca toca el puntaje (BR-MATCH-07).
 */
public enum Availability {
  OPEN_TO_WORK,
  NOT_LOOKING,
  UNSET;

  /** Un valor desconocido es `UNSET`: ante la duda no se da prioridad (BR-MATCH-09). */
  public static Availability of(String name) {
    for (Availability value : values()) {
      if (value.name().equals(name)) {
        return value;
      }
    }
    return UNSET;
  }
}
