package com.usac.jobseeker.matching.domain.service;

import com.usac.jobseeker.matching.domain.model.CandidateCard;
import com.usac.jobseeker.matching.domain.model.CandidateSignals;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;

/**
 * Construye la tarjeta enmascarada de un candidato (REQ-C-07). El titular lo escribe la persona y
 * puede contener un correo o un teléfono, así que pasa por la misma redacción que el texto que se
 * embebe (ADR-0017, decisión 2): excluir campos por nombre no basta.
 */
public final class CandidateCards {

  private static final int MAX_SKILLS = 5;

  private CandidateCards() {}

  public static CandidateCard of(CandidateSignals candidate, LocalDate today) {
    String headline =
        candidate.headline() == null || candidate.headline().isBlank()
            ? null
            : PiiRedactor.redact(candidate.headline()).text();
    List<String> skills =
        candidate.skills().stream()
            .filter(s -> s.name() != null && !s.name().isBlank())
            .sorted(
                Comparator.comparing(
                        (CandidateSignals.Skill s) -> s.level() == null ? 0 : s.level())
                    .reversed()
                    .thenComparing(CandidateSignals.Skill::name))
            .limit(MAX_SKILLS)
            .map(s -> PiiRedactor.redact(s.name()).text())
            .toList();
    String education =
        candidate.education().stream()
            .filter(e -> e.completionDate() != null && !e.completionDate().isAfter(today))
            .filter(e -> ScoreCatalogs.educationRank(e.level()) > 0)
            .max(Comparator.comparingInt(e -> ScoreCatalogs.educationRank(e.level())))
            .map(CandidateSignals.Education::level)
            .orElse(null);
    double years =
        Math.round(ProfileDimensions.yearsOf(candidate.experiences(), today) * 10) / 10.0;
    return new CandidateCard(headline, candidate.countryOfResidence(), years, education, skills);
  }
}
