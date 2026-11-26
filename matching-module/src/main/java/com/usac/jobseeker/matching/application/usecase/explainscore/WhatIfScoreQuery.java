package com.usac.jobseeker.matching.application.usecase.explainscore;

import com.usac.jobseeker.common.domain.error.FieldViolation;
import com.usac.jobseeker.common.domain.error.ValidationFailedException;
import com.usac.jobseeker.common.domain.model.AccountId;
import com.usac.jobseeker.matching.domain.model.CandidateSignals;
import com.usac.jobseeker.matching.domain.model.JobPostingId;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * «¿Qué pasaría si declarara también…?» (REQ-C-01): las habilidades e idiomas **hipotéticos** que
 * el propio candidato quiere probar. La consulta solo calcula lo que él pide; nunca sugiere
 * declarar lo que no se tiene.
 */
public record WhatIfScoreQuery(
    AccountId candidate,
    JobPostingId job,
    List<CandidateSignals.Skill> skills,
    List<CandidateSignals.Language> languages) {

  /** Máximo de hipótesis por categoría: es una prueba, no una reescritura del perfil. */
  public static final int MAX_EXTRAS = 20;

  private static final Set<String> PROFICIENCIES =
      Set.of("A1", "A2", "B1", "B2", "C1", "C2", "NATIVE");
  private static final int MAX_SKILL_LEVEL = 5;
  private static final String SKILLS = "skills";
  private static final String LANGUAGES = "languages";

  public WhatIfScoreQuery {
    Objects.requireNonNull(candidate, "candidate");
    Objects.requireNonNull(job, "job");
    skills = skills == null ? List.of() : List.copyOf(skills);
    languages = languages == null ? List.of() : List.copyOf(languages);
    List<FieldViolation> violations = new ArrayList<>();
    if (skills.size() > MAX_EXTRAS) {
      violations.add(new FieldViolation(SKILLS, "TOO_MANY"));
    }
    if (languages.size() > MAX_EXTRAS) {
      violations.add(new FieldViolation(LANGUAGES, "TOO_MANY"));
    }
    if (skills.stream().anyMatch(s -> s.name() == null || s.name().isBlank())) {
      violations.add(new FieldViolation(SKILLS, "NAME_REQUIRED"));
    }
    if (skills.stream()
        .anyMatch(s -> s.level() != null && (s.level() < 1 || s.level() > MAX_SKILL_LEVEL))) {
      violations.add(new FieldViolation(SKILLS, "LEVEL_OUT_OF_RANGE"));
    }
    if (languages.stream().anyMatch(l -> l.language() == null || l.language().isBlank())) {
      violations.add(new FieldViolation(LANGUAGES, "LANGUAGE_REQUIRED"));
    }
    if (languages.stream().anyMatch(l -> !PROFICIENCIES.contains(l.proficiency()))) {
      violations.add(new FieldViolation(LANGUAGES, "INVALID_PROFICIENCY"));
    }
    if (!violations.isEmpty()) {
      throw new ValidationFailedException(violations);
    }
  }
}
