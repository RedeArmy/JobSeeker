package com.usac.jobseeker.job.application.usecase.getjobsignals;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Lo que `matching` puede saber de una vacante: su contenido y sus requisitos, **sin** quién la
 * escribió (cuenta, nombre o empresa del reclutador) ni sus fechas de gestión. Los enumerados salen
 * como texto: el contrato entre módulos es el nombre del valor, no un tipo de `job` (ARCH-04).
 *
 * <p>`version` es la de la vacante; el consumidor la compara con la del evento que lo despertó para
 * saber si leyó algo más viejo.
 */
public record JobSnapshot(
    UUID jobPostingId,
    long version,
    String status,
    String recruitingStage,
    boolean acceptingApplications,
    String title,
    String description,
    String modality,
    String country,
    Salary salary,
    List<Requirement> requirements) {

  public JobSnapshot {
    requirements = List.copyOf(requirements);
  }

  /**
   * Rango salarial con su moneda y periodo (BR-JOB-21); cualquiera de los extremos puede faltar.
   */
  public record Salary(BigDecimal min, BigDecimal max, String currency, String period) {}

  /** Un requisito estructurado; los campos que no son de su tipo van nulos. */
  public record Requirement(
      String type,
      boolean mandatory,
      String skillName,
      Integer minLevel,
      String educationLevel,
      Integer minYears,
      String language,
      String minProficiency) {}
}
