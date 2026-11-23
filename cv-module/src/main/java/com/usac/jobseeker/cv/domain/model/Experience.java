package com.usac.jobseeker.cv.domain.model;

import com.usac.jobseeker.common.domain.error.FieldViolation;
import com.usac.jobseeker.common.domain.error.ValidationFailedException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Una experiencia laboral. Sin fecha de fin es la actual. Se construye por {@link #of}, que valida
 * todo y reúne las violaciones; el nombre de la empresa no entra en la representación canónica que
 * se embebe, pero sí se muestra al titular (`02-hld-system-design.md` §4.3).
 */
public record Experience(
    ProfileItemId id,
    String company,
    String title,
    LocalDate startDate,
    LocalDate endDate,
    String description) {

  public static final int NAME_MAX = 200;
  public static final int DESCRIPTION_MAX = 4000;

  public Experience {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(company, "company");
    Objects.requireNonNull(title, "title");
    Objects.requireNonNull(startDate, "startDate");
  }

  public static Experience of(
      ProfileItemId id,
      String company,
      String title,
      LocalDate startDate,
      LocalDate endDate,
      String description) {
    List<FieldViolation> violations = new ArrayList<>();
    String cleanCompany = TextRules.singleLine("company", company, NAME_MAX, true, violations);
    String cleanTitle = TextRules.singleLine("title", title, NAME_MAX, true, violations);
    LocalDate start = TextRules.date("startDate", startDate, true, violations);
    LocalDate end = TextRules.date("endDate", endDate, false, violations);
    TextRules.order("endDate", start, end, violations);
    String cleanDescription =
        TextRules.multiLine("description", description, DESCRIPTION_MAX, violations);
    if (!violations.isEmpty()) {
      throw new ValidationFailedException(violations);
    }
    return new Experience(id, cleanCompany, cleanTitle, start, end, cleanDescription);
  }

  public boolean isCurrent() {
    return endDate == null;
  }
}
