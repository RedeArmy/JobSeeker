package com.usac.jobseeker.cv.domain.model;

import java.util.List;

/** Los tres conjuntos del perfil de candidato, tal como se leen. Cada uno se reemplaza entero. */
public record CandidateCollections(
    List<CandidateSkill> skills,
    List<CandidateLanguage> languages,
    List<String> workAuthorizations) {

  public CandidateCollections {
    skills = List.copyOf(skills);
    languages = List.copyOf(languages);
    workAuthorizations = List.copyOf(workAuthorizations);
  }

  public static CandidateCollections empty() {
    return new CandidateCollections(List.of(), List.of(), List.of());
  }
}
