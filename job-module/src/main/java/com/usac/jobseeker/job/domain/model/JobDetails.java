package com.usac.jobseeker.job.domain.model;

import com.usac.jobseeker.common.domain.error.FieldViolation;
import com.usac.jobseeker.common.domain.error.ValidationFailedException;
import com.usac.jobseeker.common.domain.rules.FormatRules;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Lo que el reclutador escribe de la vacante: título, descripción, modalidad, país y rango salarial
 * opcional. Inmutable y siempre válido: la única forma de construirlo es {@link #of}, que reúne
 * todas las violaciones y falla una sola vez con el detalle por campo (BR-JOB-02, BR-JOB-21).
 */
public record JobDetails(
    String title, String description, WorkModality modality, String country, SalaryRange salary) {

  static final int TITLE_MAX = 150;
  static final int DESCRIPTION_MAX = 10_000;

  public JobDetails {
    Objects.requireNonNull(title, "title");
    Objects.requireNonNull(description, "description");
  }

  /** Valida y construye; lanza {@link ValidationFailedException} con todas las violaciones. */
  public static JobDetails of(
      String title,
      String description,
      WorkModality modality,
      String country,
      BigDecimal salaryMin,
      BigDecimal salaryMax,
      String salaryCurrency,
      SalaryPeriod salaryPeriod) {
    List<FieldViolation> violations = new ArrayList<>();
    String cleanTitle = FormatRules.singleLine("title", title, TITLE_MAX, true, violations);
    String cleanDescription =
        FormatRules.multiLine("description", description, DESCRIPTION_MAX, true, violations);
    String cleanCountry = FormatRules.country("country", country, false, violations);
    SalaryRange salary =
        SalaryRange.of(salaryMin, salaryMax, salaryCurrency, salaryPeriod, violations);
    if (!violations.isEmpty()) {
      throw new ValidationFailedException(violations);
    }
    return new JobDetails(cleanTitle, cleanDescription, modality, cleanCountry, salary);
  }
}
