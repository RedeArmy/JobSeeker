package com.usac.jobseeker.matching.domain.model;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

/**
 * Lo que `matching` necesita de una vacante para calcular su embedding: su contenido y sus
 * requisitos. Es **propia** de `matching` (ARCH-04): lo que entrega `job` se traduce a este tipo en
 * una capa anticorrupción, y no trae quién la escribió ni dato alguno del reclutador, así que no
 * hay dónde guardarlos.
 *
 * <p>País, modalidad y salario no están aquí: son dimensiones estructurales del puntaje y no entran
 * en el texto que se embebe, igual que en el perfil. Llegan cuando se calcule el puntaje.
 *
 * @param published si la vacante está publicada ahora; solo una publicada es candidata a puntuarse
 *     (BR-JOB-06, BR-JOB-11)
 * @param reviewing si el reclutador la tiene en `REVIEWING`, que la saca de los sugeridos
 *     (BR-MATCH-08)
 */
public record JobSignals(
    JobPostingId jobId,
    long version,
    boolean published,
    boolean reviewing,
    String title,
    String description,
    String modality,
    String country,
    Salary salary,
    List<Requirement> requirements) {

  public JobSignals {
    Objects.requireNonNull(jobId, "jobId");
    Objects.requireNonNull(title, "title");
    Objects.requireNonNull(description, "description");
    requirements = List.copyOf(requirements);
  }

  /** Una vacante sin modalidad, país ni salario: lo que basta para representarla textualmente. */
  public JobSignals(
      JobPostingId jobId,
      long version,
      boolean published,
      boolean reviewing,
      String title,
      String description,
      List<Requirement> requirements) {
    this(jobId, version, published, reviewing, title, description, null, null, null, requirements);
  }

  /** Rango salarial con moneda y periodo (BR-JOB-21); cualquier extremo puede faltar. */
  public record Salary(BigDecimal min, BigDecimal max, String currency, String period) {}

  /**
   * Un requisito estructurado. Los campos que no son de su tipo van nulos.
   *
   * @param type `SKILL`, `EDUCATION`, `EXPERIENCE_YEARS` o `LANGUAGE`
   */
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
