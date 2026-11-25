package com.usac.jobseeker.cv.domain.model;

import com.usac.jobseeker.common.domain.error.FieldViolation;
import com.usac.jobseeker.common.domain.error.ValidationFailedException;
import com.usac.jobseeker.common.domain.rules.FormatRules;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Un idioma que habla el candidato y su nivel de dominio. El código es ISO 639-1 en minúsculas
 * (`es`, `en`). A un idioma lo evalúan las reglas de elegibilidad de nivel de idioma (BR-ELIG-01),
 * así que repetir el mismo idioma con dos niveles sería ambiguo y se rechaza.
 */
public record CandidateLanguage(String language, LanguageProficiency proficiency) {

  public static final int MAX_PER_PROFILE = 30;

  /** Valida un conjunto completo y devuelve sus elementos con el código en su forma canónica. */
  public static List<CandidateLanguage> setOf(List<CandidateLanguage> drafts) {
    List<FieldViolation> violations = new ArrayList<>();
    List<CandidateLanguage> clean = new ArrayList<>();
    Set<String> seen = new HashSet<>();
    if (drafts.size() > MAX_PER_PROFILE) {
      violations.add(new FieldViolation("languages", FormatRules.TOO_MANY));
    }
    for (int i = 0; i < drafts.size(); i++) {
      CandidateLanguage draft = drafts.get(i);
      String field = "languages[" + i + "]";
      String code = FormatRules.language(field + ".language", draft.language(), violations);
      if (draft.proficiency() == null) {
        violations.add(new FieldViolation(field + ".proficiency", FormatRules.REQUIRED));
      }
      if (code != null && !seen.add(code)) {
        violations.add(new FieldViolation(field + ".language", FormatRules.DUPLICATE));
      }
      if (code != null && draft.proficiency() != null) {
        clean.add(new CandidateLanguage(code, draft.proficiency()));
      }
    }
    if (!violations.isEmpty()) {
      throw new ValidationFailedException(violations);
    }
    return List.copyOf(clean);
  }
}
