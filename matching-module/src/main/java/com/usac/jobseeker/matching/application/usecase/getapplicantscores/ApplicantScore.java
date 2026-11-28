package com.usac.jobseeker.matching.application.usecase.getapplicantscores;

import java.util.List;
import java.util.UUID;

/**
 * El puntaje de un postulante en la vacante, con su desglose (BR-MATCH-02), o pendiente: nunca un
 * número a medias (tdd/ats-tracker-modules.md §9). Todo con tipos simples, para que `ats` lo lea
 * sin conocer el dominio de `matching`.
 *
 * @param status `OK`, o `PENDING` si aún falta el vector del perfil o de la vacante
 * @param overall de 0 a 100; nulo si está pendiente
 * @param calibrated falso mientras no exista la calibración: el porcentaje es preliminar (ADR-0030)
 * @param appliedOverall el puntaje que tenía al postular; nulo si entonces estaba pendiente
 * @param breakdown nulo si está pendiente
 */
public record ApplicantScore(
    UUID candidateProfileId,
    String status,
    Double overall,
    boolean calibrated,
    String algorithmVersion,
    Double appliedOverall,
    Breakdown breakdown) {

  public static final String OK = "OK";
  public static final String PENDING = "PENDING";

  /** El desglose; ver `ScoreBreakdown` de `matching`. */
  public record Breakdown(
      double completeness,
      double alpha,
      double structured,
      double semantic,
      List<Dimension> dimensions) {

    public Breakdown {
      dimensions = List.copyOf(dimensions);
    }
  }

  /** Una fila del desglose; el peso ya está renormalizado entre las dimensiones disponibles. */
  public record Dimension(
      String dimension, String status, double score, double weight, String explanation) {}
}
