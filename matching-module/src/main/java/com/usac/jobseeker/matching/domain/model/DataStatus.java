package com.usac.jobseeker.matching.domain.model;

/**
 * Por qué una dimensión cuenta o no en el puntaje. Distinguirlo es lo que permite no castigar lo
 * que simplemente no se sabe (ADR-0017, decisión 6): una dimensión que no está `AVAILABLE` se
 * excluye y los pesos se renormalizan entre las disponibles.
 */
public enum DataStatus {
  /** Hay dato de ambos lados y se comparó. */
  AVAILABLE,
  /** La vacante pide algo y el candidato no declaró el dato: baja la completitud. */
  NOT_DECLARED,
  /**
   * La vacante no define nada en esta dimensión: no es culpa del candidato y no baja la
   * completitud.
   */
  NOT_APPLICABLE,
  /**
   * Hay dato de ambos lados pero no es comparable, p. ej. salarios en monedas distintas
   * (BR-MATCH-10).
   */
  NOT_COMPARABLE
}
