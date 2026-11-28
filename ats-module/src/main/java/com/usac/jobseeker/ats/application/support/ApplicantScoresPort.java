package com.usac.jobseeker.ats.application.support;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Los puntajes de los postulantes, según `matching` (ADR-0031): `ats` no lee sus tablas ni duplica
 * el puntaje (regla 6 del TDD, D4). Con tipos propios de `ats` (ARCH-04).
 */
public interface ApplicantScoresPort {

  /**
   * @param status `OK`, o `PENDING` si aún falta un vector: nunca un número a medias
   * @param overall de 0 a 100; nulo si está pendiente
   * @param calibrated falso mientras el puntaje sea preliminar (ADR-0030)
   * @param appliedOverall el que tenía al postular; nulo si entonces estaba pendiente
   * @param breakdown nulo si está pendiente
   */
  record Score(
      String status,
      Double overall,
      boolean calibrated,
      String algorithmVersion,
      Double appliedOverall,
      Breakdown breakdown) {

    public static final String PENDING = "PENDING";

    public static Score notCalculatedYet() {
      return new Score(PENDING, null, false, null, null, null);
    }

    public boolean isPending() {
      return overall == null;
    }
  }

  /** El desglose por dimensión (BR-MATCH-02). */
  record Breakdown(
      double completeness,
      double alpha,
      double structured,
      double semantic,
      List<Dimension> dimensions) {

    public Breakdown {
      dimensions = List.copyOf(dimensions);
    }
  }

  record Dimension(
      String dimension, String status, double score, double weight, String explanation) {}

  /** El puntaje de cada perfil pedido en la vacante, en una sola lectura. */
  Map<UUID, Score> of(UUID jobPostingId, Collection<UUID> candidateProfileIds);
}
