package com.usac.jobseeker.matching.domain.service;

import com.usac.jobseeker.matching.domain.model.CandidateSignals;
import com.usac.jobseeker.matching.domain.model.DimensionResult;
import com.usac.jobseeker.matching.domain.model.JobSignals;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Las dimensiones que comparan **requisitos** de la vacante con lo que el candidato declara:
 * habilidades (35 %) e idiomas (5 %). Un requisito obligatorio pesa `mandatoryFactor` veces uno
 * deseable. Funciones puras, sin estado ni dependencias.
 */
final class SkillDimensions {

  private static final String SKILL = "SKILL";
  private static final String LANGUAGE = "LANGUAGE";
  private static final int UNKNOWN = 0;
  private static final double FULL_CREDIT = 1.0;

  private SkillDimensions() {}

  /**
   * Cubrir un requisito es declarar la habilidad; si pide nivel y el candidato lo declaró, el
   * crédito es proporcional (sin pasar de 1); si no declaró nivel no se puede verificar y el
   * crédito es la mitad. Así declarar un nivel que cumple nunca baja el puntaje frente a omitirlo
   * (control C3).
   */
  static DimensionResult skills(
      CandidateSignals candidate, JobSignals job, double mandatoryFactor) {
    List<JobSignals.Requirement> required =
        job.requirements().stream()
            .filter(r -> SKILL.equals(r.type()) && !ScoreCatalogs.skillKey(r.skillName()).isEmpty())
            .toList();
    if (required.isEmpty()) {
      return DimensionResult.notApplicable("La vacante no pide habilidades.");
    }
    if (candidate.skills().isEmpty()) {
      return DimensionResult.notDeclared("Dato no declarado: habilidades.");
    }
    Map<String, Integer> declared = new HashMap<>();
    for (CandidateSignals.Skill skill : candidate.skills()) {
      declared.merge(
          ScoreCatalogs.skillKey(skill.name()),
          skill.level() == null ? UNKNOWN : skill.level(),
          Math::max);
    }
    double got = 0;
    double total = 0;
    int covered = 0;
    List<String> missing = new ArrayList<>();
    for (JobSignals.Requirement requirement : required) {
      double weight = requirement.mandatory() ? mandatoryFactor : 1;
      total += weight;
      String key = ScoreCatalogs.skillKey(requirement.skillName());
      if (!declared.containsKey(key)) {
        missing.add(requirement.skillName());
        continue;
      }
      covered++;
      int level = declared.get(key);
      got += weight * levelCredit(level, requirement.minLevel());
    }
    String text =
        missing.isEmpty()
            ? "Cubre las " + required.size() + " habilidades requeridas."
            : "Cubre "
                + covered
                + " de "
                + required.size()
                + " habilidades requeridas; faltan: "
                + String.join(", ", missing)
                + ".";
    return DimensionResult.available(got / total, text);
  }

  /** Mismo criterio que las habilidades, con el orden A1 a C2 y nativo; el idioma ausente da 0. */
  static DimensionResult languages(
      CandidateSignals candidate, JobSignals job, double mandatoryFactor) {
    List<JobSignals.Requirement> required =
        job.requirements().stream()
            .filter(
                r -> LANGUAGE.equals(r.type()) && !ScoreCatalogs.nameKey(r.language()).isEmpty())
            .toList();
    if (required.isEmpty()) {
      return DimensionResult.notApplicable("La vacante no pide idiomas.");
    }
    if (candidate.languages().isEmpty()) {
      return DimensionResult.notDeclared("Dato no declarado: idiomas.");
    }
    Map<String, Integer> declared = new HashMap<>();
    for (CandidateSignals.Language language : candidate.languages()) {
      declared.merge(
          ScoreCatalogs.nameKey(language.language()),
          ScoreCatalogs.proficiencyRank(language.proficiency()),
          Math::max);
    }
    double got = 0;
    double total = 0;
    int covered = 0;
    List<String> missing = new ArrayList<>();
    for (JobSignals.Requirement requirement : required) {
      double weight = requirement.mandatory() ? mandatoryFactor : 1;
      total += weight;
      Integer rank = declared.get(ScoreCatalogs.nameKey(requirement.language()));
      if (rank == null) {
        missing.add(requirement.language());
        continue;
      }
      double credit =
          proficiencyCredit(rank, ScoreCatalogs.proficiencyRank(requirement.minProficiency()));
      if (credit >= FULL_CREDIT) {
        covered++;
      } else {
        missing.add(requirement.language() + " (nivel " + requirement.minProficiency() + ")");
      }
      got += weight * credit;
    }
    String text =
        missing.isEmpty()
            ? "Cumple los " + required.size() + " idiomas requeridos."
            : "Cumple "
                + covered
                + " de "
                + required.size()
                + " idiomas requeridos; falta: "
                + String.join(", ", missing)
                + ".";
    return DimensionResult.available(got / total, text);
  }

  private static double levelCredit(int level, Integer minLevel) {
    if (minLevel == null || minLevel <= UNKNOWN) {
      return 1;
    }
    if (level == UNKNOWN) {
      return 0.5;
    }
    return Math.min(1.0, (double) level / minLevel);
  }

  private static double proficiencyCredit(int rank, int requiredRank) {
    if (requiredRank == UNKNOWN || rank == UNKNOWN) {
      return rank == UNKNOWN ? 0.5 : 1;
    }
    return Math.min(1.0, (double) rank / requiredRank);
  }
}
