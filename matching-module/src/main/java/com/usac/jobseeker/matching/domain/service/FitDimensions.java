package com.usac.jobseeker.matching.domain.service;

import com.usac.jobseeker.matching.domain.model.CandidateSignals;
import com.usac.jobseeker.matching.domain.model.DimensionResult;
import com.usac.jobseeker.matching.domain.model.JobSignals;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/** Las dimensiones de ajuste: ubicación y modalidad (10 %) y salario (10 %). Funciones puras. */
final class FitDimensions {

  private static final String REMOTE = "REMOTE";
  private static final String HYBRID = "HYBRID";
  private static final int MONTHS_PER_YEAR = 12;
  private static final String MONTH = "MONTH";
  // Los ajustes toman solo estos valores exactos (1, 0 y 0,5), así que compararlos es seguro.
  private static final double FULL_FIT = 1.0;
  private static final double NO_FIT = 0.0;

  private FitDimensions() {}

  /**
   * Dos componentes con la misma importancia: la modalidad y el país. En una vacante remota el país
   * no importa. Si el candidato no declaró un componente, ese componente se omite y no se supone;
   * si no declaró ninguno, la dimensión queda sin declarar.
   */
  static DimensionResult locationModality(CandidateSignals candidate, JobSignals job) {
    boolean countryMatters = !REMOTE.equals(job.modality()) && notBlank(job.country());
    if (!notBlank(job.modality()) && !countryMatters) {
      return DimensionResult.notApplicable("La vacante no define modalidad ni país.");
    }
    List<Component> components = new ArrayList<>();
    modalityComponent(candidate, job).ifPresent(components::add);
    if (countryMatters) {
      countryComponent(candidate, job).ifPresent(components::add);
    }
    if (components.isEmpty()) {
      return DimensionResult.notDeclared("Dato no declarado: modalidad o país.");
    }
    double mean = components.stream().mapToDouble(Component::fit).average().orElse(0);
    String notes = components.stream().map(Component::note).collect(Collectors.joining("; "));
    return DimensionResult.available(mean, capitalize(notes) + ".");
  }

  /** Un componente del ajuste: qué tanto encaja (0 a 1) y cómo se dice en el desglose. */
  private record Component(double fit, String note) {}

  private static Optional<Component> modalityComponent(CandidateSignals candidate, JobSignals job) {
    if (!notBlank(job.modality()) || !notBlank(candidate.desiredModality())) {
      return Optional.empty();
    }
    double fit = modalityFit(job.modality(), candidate.desiredModality());
    return Optional.of(new Component(fit, modalityNote(fit)));
  }

  private static Optional<Component> countryComponent(CandidateSignals candidate, JobSignals job) {
    if (!notBlank(candidate.countryOfResidence())) {
      return Optional.empty();
    }
    double fit = countryFit(candidate, job);
    return Optional.of(new Component(fit, countryNote(fit)));
  }

  private static String modalityNote(double fit) {
    if (fit == FULL_FIT) {
      return "modalidad compatible";
    }
    return fit == NO_FIT ? "modalidad incompatible" : "modalidad parcialmente compatible";
  }

  private static String countryNote(double fit) {
    if (fit == FULL_FIT) {
      return "mismo país";
    }
    return fit == NO_FIT
        ? "otro país sin disposición a reubicarse"
        : "otro país con disposición a reubicarse";
  }

  /**
   * Compara el rango que el candidato espera con el que ofrece la vacante, en la **misma moneda** y
   * llevados al mismo periodo (anual = 12 × mensual); no se convierte entre monedas (BR-MATCH-10):
   * si difieren, la dimensión se excluye y el desglose lo dice. Si el piso de lo que espera cabe
   * dentro de lo que se ofrece es 1; si no, baja en proporción a lo que se pasa.
   */
  static DimensionResult salary(CandidateSignals candidate, JobSignals job) {
    JobSignals.Salary offered = job.salary();
    if (offered == null || (offered.min() == null && offered.max() == null)) {
      return DimensionResult.notApplicable("La vacante no publica rango salarial.");
    }
    CandidateSignals.Salary expected = candidate.salary();
    if (expected == null || (expected.min() == null && expected.max() == null)) {
      return DimensionResult.notDeclared("Dato no declarado: expectativa salarial.");
    }
    if (!offered.currency().equals(expected.currency())) {
      return DimensionResult.notComparable(
          "Las monedas difieren ("
              + expected.currency()
              + " y "
              + offered.currency()
              + "): no se compara.");
    }
    double floor =
        annual(expected.min() != null ? expected.min() : expected.max(), expected.period());
    double ceiling =
        annual(offered.max() != null ? offered.max() : offered.min(), offered.period());
    if (floor <= ceiling) {
      return DimensionResult.available(1, "Su expectativa es compatible con el rango ofrecido.");
    }
    double score = Math.max(0.0, 1 - (floor - ceiling) / floor);
    return DimensionResult.available(score, "Su expectativa supera el rango ofrecido.");
  }

  private static double modalityFit(String job, String candidate) {
    if (job.equals(candidate)) {
      return 1;
    }
    return HYBRID.equals(job) || HYBRID.equals(candidate) ? 0.5 : 0;
  }

  private static double countryFit(CandidateSignals candidate, JobSignals job) {
    if (job.country().equals(candidate.countryOfResidence())) {
      return 1;
    }
    return candidate.relocationWillingness() ? 0.5 : 0;
  }

  private static double annual(BigDecimal amount, String period) {
    double value = amount.doubleValue();
    return MONTH.equals(period) ? value * MONTHS_PER_YEAR : value;
  }

  private static boolean notBlank(String value) {
    return value != null && !value.isBlank();
  }

  private static String capitalize(String text) {
    return text.substring(0, 1).toUpperCase(java.util.Locale.ROOT) + text.substring(1);
  }
}
