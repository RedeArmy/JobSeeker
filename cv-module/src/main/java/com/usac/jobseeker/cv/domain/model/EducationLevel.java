package com.usac.jobseeker.cv.domain.model;

/**
 * Nivel académico normalizado. Es distinto del título, que es texto libre: las reglas de
 * elegibilidad `ACADEMIC_LEVEL` (BR-ELIG-01) comparan contra el nivel más alto declarado, y un
 * texto libre no es un valor contra el que se pueda comparar un umbral. El orden de declaración es
 * el del nivel, de menor a mayor.
 */
public enum EducationLevel {
  SECONDARY,
  TECHNICAL,
  BACHELOR,
  POSTGRAD_SPECIALIZATION,
  MASTERS,
  DOCTORATE
}
