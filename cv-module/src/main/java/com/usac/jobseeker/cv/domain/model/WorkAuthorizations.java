package com.usac.jobseeker.cv.domain.model;

import com.usac.jobseeker.common.domain.error.FieldViolation;
import com.usac.jobseeker.common.domain.error.ValidationFailedException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Los países donde el candidato declara tener autorización legal para trabajar (BR-ELIG-01,
 * `WORK_AUTHORIZATION`). Es un conjunto de códigos ISO 3166-1 alfa-2 y no un único valor, a
 * diferencia del país de residencia, porque una persona puede tenerla en más de un país.
 */
public final class WorkAuthorizations {

  public static final int MAX_PER_PROFILE = 50;

  private WorkAuthorizations() {}

  /** Valida el conjunto y devuelve los códigos en mayúsculas, sin repeticiones. */
  public static List<String> setOf(List<String> drafts) {
    List<FieldViolation> violations = new ArrayList<>();
    List<String> clean = new ArrayList<>();
    Set<String> seen = new HashSet<>();
    if (drafts.size() > MAX_PER_PROFILE) {
      violations.add(new FieldViolation("workAuthorizations", CandidateRules.TOO_MANY));
    }
    for (int i = 0; i < drafts.size(); i++) {
      String field = "workAuthorizations[" + i + "]";
      String code = CandidateRules.country(field, drafts.get(i), true, violations);
      if (code != null && !seen.add(code)) {
        violations.add(new FieldViolation(field, CandidateRules.DUPLICATE));
      }
      if (code != null) {
        clean.add(code);
      }
    }
    if (!violations.isEmpty()) {
      throw new ValidationFailedException(violations);
    }
    return List.copyOf(clean);
  }
}
