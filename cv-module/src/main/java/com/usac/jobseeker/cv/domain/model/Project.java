package com.usac.jobseeker.cv.domain.model;

import com.usac.jobseeker.common.domain.error.FieldViolation;
import com.usac.jobseeker.common.domain.error.ValidationFailedException;
import com.usac.jobseeker.common.domain.rules.FormatRules;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Un proyecto laboral o personal. Sin fecha de fin está en curso. */
public record Project(
    ProfileItemId id,
    String name,
    String description,
    ProjectType type,
    String role,
    LocalDate startDate,
    LocalDate endDate,
    String url) {

  public static final int NAME_MAX = 200;
  public static final int DESCRIPTION_MAX = 4000;

  public Project {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(name, "name");
    Objects.requireNonNull(type, "type");
  }

  public static Project of(
      ProfileItemId id,
      String name,
      String description,
      ProjectType type,
      String role,
      LocalDate startDate,
      LocalDate endDate,
      String url) {
    List<FieldViolation> violations = new ArrayList<>();
    String cleanName = FormatRules.singleLine("name", name, NAME_MAX, true, violations);
    String cleanDescription =
        FormatRules.multiLine("description", description, DESCRIPTION_MAX, false, violations);
    if (type == null) {
      violations.add(new FieldViolation("type", "REQUIRED"));
    }
    String cleanRole = FormatRules.singleLine("role", role, NAME_MAX, false, violations);
    LocalDate start = TextRules.date("startDate", startDate, false, violations);
    LocalDate end = TextRules.date("endDate", endDate, false, violations);
    TextRules.order("endDate", start, end, violations);
    String cleanUrl = TextRules.url("url", url, violations);
    if (!violations.isEmpty()) {
      throw new ValidationFailedException(violations);
    }
    return new Project(id, cleanName, cleanDescription, type, cleanRole, start, end, cleanUrl);
  }
}
