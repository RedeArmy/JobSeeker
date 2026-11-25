package com.usac.jobseeker.cv.domain.model;

import com.usac.jobseeker.common.domain.error.FieldViolation;
import com.usac.jobseeker.common.domain.error.ValidationFailedException;
import com.usac.jobseeker.common.domain.rules.FormatRules;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Una certificación profesional. Sin fecha de expiración no expira. */
public record Certification(
    ProfileItemId id,
    String name,
    String issuingOrganization,
    LocalDate issueDate,
    LocalDate expirationDate,
    String credentialUrl) {

  public static final int NAME_MAX = 200;

  public Certification {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(name, "name");
    Objects.requireNonNull(issuingOrganization, "issuingOrganization");
  }

  public static Certification of(
      ProfileItemId id,
      String name,
      String issuingOrganization,
      LocalDate issueDate,
      LocalDate expirationDate,
      String credentialUrl) {
    List<FieldViolation> violations = new ArrayList<>();
    String cleanName = FormatRules.singleLine("name", name, NAME_MAX, true, violations);
    String cleanIssuer =
        FormatRules.singleLine(
            "issuingOrganization", issuingOrganization, NAME_MAX, true, violations);
    LocalDate issue = TextRules.date("issueDate", issueDate, false, violations);
    LocalDate expiration = TextRules.date("expirationDate", expirationDate, false, violations);
    TextRules.order("expirationDate", issue, expiration, violations);
    String url = TextRules.url("credentialUrl", credentialUrl, violations);
    if (!violations.isEmpty()) {
      throw new ValidationFailedException(violations);
    }
    return new Certification(id, cleanName, cleanIssuer, issue, expiration, url);
  }
}
