package com.usac.jobseeker.matching.domain.service;

import com.usac.jobseeker.matching.domain.model.CandidateSignals;
import com.usac.jobseeker.matching.domain.model.DimensionResult;
import com.usac.jobseeker.matching.domain.model.JobSignals;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/** Las dimensiones de trayectoria: experiencia (25 %) y formación (15 %). Funciones puras. */
final class ProfileDimensions {

  private static final String EXPERIENCE_TYPE = "EXPERIENCE_YEARS";
  private static final String EDUCATION_TYPE = "EDUCATION";
  private static final double DAYS_PER_YEAR = 365.25;
  private static final Locale ES = Locale.forLanguageTag("es");

  private ProfileDimensions() {}

  /**
   * Años de experiencia: el total de la unión de los periodos declarados, de modo que dos empleos
   * simultáneos no cuentan doble. Es el total y no los años «relevantes» al puesto: medir
   * relevancia exige la taxonomía de habilidades (TD-038).
   */
  static double yearsOf(List<CandidateSignals.Experience> experiences, LocalDate today) {
    List<LocalDate[]> spans = new ArrayList<>();
    for (CandidateSignals.Experience experience : experiences) {
      LocalDate start = experience.startDate();
      LocalDate end = experience.endDate() == null ? today : experience.endDate();
      if (start != null && !start.isAfter(end)) {
        spans.add(new LocalDate[] {start, end});
      }
    }
    spans.sort(Comparator.comparing(span -> span[0]));
    long days = 0;
    LocalDate cursor = null;
    for (LocalDate[] span : spans) {
      LocalDate from = cursor != null && cursor.isAfter(span[0]) ? cursor : span[0];
      if (span[1].isAfter(from)) {
        days += ChronoUnit.DAYS.between(from, span[1]);
        cursor = span[1];
      }
    }
    return days / DAYS_PER_YEAR;
  }

  static DimensionResult experience(CandidateSignals candidate, JobSignals job, LocalDate today) {
    Optional<Integer> required =
        job.requirements().stream()
            .filter(r -> EXPERIENCE_TYPE.equals(r.type()) && r.minYears() != null)
            .map(JobSignals.Requirement::minYears)
            .max(Integer::compare);
    if (required.isEmpty()) {
      return DimensionResult.notApplicable("La vacante no pide años de experiencia.");
    }
    if (candidate.experiences().isEmpty()) {
      return DimensionResult.notDeclared("Dato no declarado: experiencia.");
    }
    double years = yearsOf(candidate.experiences(), today);
    int minimum = required.get();
    double score = minimum <= 0 ? 1 : Math.min(1.0, years / minimum);
    String relation = years >= minimum ? "cumple" : "no alcanza";
    return DimensionResult.available(
        score,
        String.format(
            ES,
            "Acredita %.1f años de experiencia; la vacante pide %d: %s.",
            years,
            minimum,
            relation));
  }

  /**
   * Cuenta el nivel más alto **terminado**: un estudio sin fecha de finalización está en curso y no
   * acredita todavía el título. El nombre de la institución no entra nunca (ADR-0017, decisión 2).
   */
  static DimensionResult education(CandidateSignals candidate, JobSignals job, LocalDate today) {
    int required =
        job.requirements().stream()
            .filter(r -> EDUCATION_TYPE.equals(r.type()))
            .mapToInt(r -> ScoreCatalogs.educationRank(r.educationLevel()))
            .max()
            .orElse(0);
    if (required == 0) {
      return DimensionResult.notApplicable("La vacante no pide un nivel de formación.");
    }
    int highest =
        candidate.education().stream()
            .filter(e -> e.completionDate() != null && !e.completionDate().isAfter(today))
            .mapToInt(e -> ScoreCatalogs.educationRank(e.level()))
            .max()
            .orElse(0);
    if (highest == 0) {
      return DimensionResult.notDeclared("Dato no declarado: formación terminada.");
    }
    double score = Math.min(1.0, (double) highest / required);
    return DimensionResult.available(
        score,
        highest >= required
            ? "Su formación terminada alcanza el nivel que pide la vacante."
            : "Su formación terminada está por debajo del nivel que pide la vacante.");
  }
}
