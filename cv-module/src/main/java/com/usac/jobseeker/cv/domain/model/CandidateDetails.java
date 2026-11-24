package com.usac.jobseeker.cv.domain.model;

import com.usac.jobseeker.common.domain.error.FieldViolation;
import com.usac.jobseeker.common.domain.error.ValidationFailedException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Lo que el candidato edita de su perfil: contacto y datos de elegibilidad (país, modalidad,
 * jornada, disposición a reubicarse y expectativa salarial). La descubribilidad y la disponibilidad
 * se editan aparte, con sus propios casos de uso y eventos, porque no disparan lo mismo.
 *
 * <p>Se construye solo por {@link #of}, que normaliza y valida; reúne todas las violaciones y nunca
 * incluye el valor rechazado, que puede ser un dato personal (BR-DATA-01).
 */
public record CandidateDetails(
    ContactInfo contact,
    String countryOfResidence,
    WorkModality desiredModality,
    WorkSchedule preferredWorkSchedule,
    boolean relocationWillingness,
    SalaryExpectation salary) {

  public CandidateDetails {
    Objects.requireNonNull(contact, "contact");
  }

  public static CandidateDetails of(
      String contactEmail,
      String contactPhone,
      String countryOfResidence,
      WorkModality desiredModality,
      WorkSchedule preferredWorkSchedule,
      Boolean relocationWillingness,
      BigDecimal salaryMin,
      BigDecimal salaryMax,
      String salaryCurrency,
      SalaryPeriod salaryPeriod) {
    List<FieldViolation> violations = new ArrayList<>();
    ContactInfo contact = ContactInfo.of(contactEmail, contactPhone, violations);
    String country =
        CandidateRules.country("countryOfResidence", countryOfResidence, false, violations);
    SalaryExpectation salary =
        SalaryExpectation.of(salaryMin, salaryMax, salaryCurrency, salaryPeriod, violations);
    if (!violations.isEmpty()) {
      throw new ValidationFailedException(violations);
    }
    // `null` en la disposición a reubicarse es «no lo dijo», que equivale a no.
    return new CandidateDetails(
        contact,
        country,
        desiredModality,
        preferredWorkSchedule,
        Boolean.TRUE.equals(relocationWillingness),
        salary);
  }
}
