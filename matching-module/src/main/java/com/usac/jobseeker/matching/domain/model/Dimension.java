package com.usac.jobseeker.matching.domain.model;

/**
 * Las seis dimensiones de la capa estructurada del puntaje (BR-MATCH-01). La afinidad semántica no
 * es una de ellas: se combina aparte, con un `α` visible en el desglose (ADR-0017, decisión 4).
 */
public enum Dimension {
  SKILLS,
  EXPERIENCE,
  EDUCATION,
  LOCATION_MODALITY,
  SALARY,
  LANGUAGES
}
