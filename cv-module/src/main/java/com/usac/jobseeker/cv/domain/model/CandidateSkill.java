package com.usac.jobseeker.cv.domain.model;

import com.usac.jobseeker.common.domain.error.FieldViolation;
import com.usac.jobseeker.common.domain.error.ValidationFailedException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Una habilidad que el candidato declara, tal como la escribe. Es texto libre hasta que exista la
 * taxonomía (ADR-0019, que ya admite una habilidad sin enlace); el nivel es opcional y va de 1
 * (básico) a 4 (experto). El conjunto no tiene identidad propia: se reemplaza entero.
 */
public record CandidateSkill(String name, Integer level) {

  public static final int NAME_MAX = 100;
  public static final int MAX_PER_PROFILE = 100;
  public static final int LEVEL_MIN = 1;
  public static final int LEVEL_MAX = 4;

  public CandidateSkill {
    Objects.requireNonNull(name, "name");
  }

  /**
   * Valida un conjunto completo y devuelve sus elementos normalizados. «Java» y « java » son la
   * misma habilidad: un duplicado es un error, no algo que se descarte en silencio, para que la
   * persona vea qué escribió dos veces.
   */
  public static List<CandidateSkill> setOf(List<CandidateSkill> drafts) {
    List<FieldViolation> violations = new ArrayList<>();
    List<CandidateSkill> clean = new ArrayList<>();
    Set<String> seen = new HashSet<>();
    if (drafts.size() > MAX_PER_PROFILE) {
      violations.add(new FieldViolation("skills", CandidateRules.TOO_MANY));
    }
    for (int i = 0; i < drafts.size(); i++) {
      CandidateSkill draft = drafts.get(i);
      String field = "skills[" + i + "]";
      String name = TextRules.singleLine(field + ".name", draft.name(), NAME_MAX, true, violations);
      Integer level = draft.level();
      if (level != null && (level < LEVEL_MIN || level > LEVEL_MAX)) {
        violations.add(new FieldViolation(field + ".level", "OUT_OF_RANGE"));
      }
      if (name != null && !seen.add(CandidateRules.sameSkillKey(name))) {
        violations.add(new FieldViolation(field + ".name", CandidateRules.DUPLICATE));
      }
      if (name != null) {
        clean.add(new CandidateSkill(name, level));
      }
    }
    if (!violations.isEmpty()) {
      throw new ValidationFailedException(violations);
    }
    return List.copyOf(clean);
  }
}
