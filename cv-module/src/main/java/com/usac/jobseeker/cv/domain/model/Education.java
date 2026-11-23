package com.usac.jobseeker.cv.domain.model;

import com.usac.jobseeker.common.domain.error.FieldViolation;
import com.usac.jobseeker.common.domain.error.ValidationFailedException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Una formación académica. Sin fecha de finalización está en curso. El nivel normalizado es
 * opcional, pero sin él la formación no cuenta para una regla de elegibilidad de nivel académico
 * (BR-ELIG-01, BR-ELIG-08: el dato ausente se trata como incumplido).
 */
public record Education(
    ProfileItemId id,
    String institution,
    String degree,
    EducationLevel level,
    LocalDate completionDate) {

  public static final int NAME_MAX = 200;

  public Education {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(institution, "institution");
    Objects.requireNonNull(degree, "degree");
  }

  public static Education of(
      ProfileItemId id,
      String institution,
      String degree,
      EducationLevel level,
      LocalDate completionDate) {
    List<FieldViolation> violations = new ArrayList<>();
    String cleanInstitution =
        TextRules.singleLine("institution", institution, NAME_MAX, true, violations);
    String cleanDegree = TextRules.singleLine("degree", degree, NAME_MAX, true, violations);
    LocalDate completion = TextRules.date("completionDate", completionDate, false, violations);
    if (!violations.isEmpty()) {
      throw new ValidationFailedException(violations);
    }
    return new Education(id, cleanInstitution, cleanDegree, level, completion);
  }

  public boolean isInProgress() {
    return completionDate == null;
  }
}
