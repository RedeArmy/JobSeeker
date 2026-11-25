package com.usac.jobseeker.job.domain.model;

/**
 * Nivel académico mínimo de un requisito. El orden de declaración es el del nivel, de menor a
 * mayor. Es la misma escala que usa `cv` para el perfil, pero propia de `job`: un módulo no importa
 * tipos de otro (ARCH-04), así que el contrato entre ambos es el nombre del valor.
 */
public enum EducationLevel {
  SECONDARY,
  TECHNICAL,
  BACHELOR,
  POSTGRAD_SPECIALIZATION,
  MASTERS,
  DOCTORATE
}
