package com.usac.jobseeker.job.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.usac.jobseeker.common.domain.error.FieldViolation;
import com.usac.jobseeker.common.domain.error.ValidationFailedException;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class JobDetailsTest {

  private static List<FieldViolation> violationsOf(
      String title,
      String description,
      String country,
      BigDecimal min,
      BigDecimal max,
      String currency,
      SalaryPeriod period) {
    try {
      JobDetails.of(title, description, null, country, min, max, currency, period);
    } catch (ValidationFailedException failure) {
      return failure.violations();
    }
    return List.of();
  }

  private static FieldViolation v(String field, String code) {
    return new FieldViolation(field, code);
  }

  @Test
  void aCompletePostingNormalizesTheTextAndTheCountry() {
    JobDetails details =
        JobDetails.of(
            "  Ingeniero  ",
            " Texto\ncon salto ",
            WorkModality.HYBRID,
            " gt ",
            null,
            null,
            null,
            null);

    assertThat(details.title()).isEqualTo("Ingeniero");
    assertThat(details.description()).isEqualTo("Texto\ncon salto");
    assertThat(details.country()).isEqualTo("GT");
    assertThat(details.salary()).isNull();
  }

  @Test
  void titleAndDescriptionAreRequired() {
    assertThat(violationsOf(" ", null, null, null, null, null, null))
        .containsExactlyInAnyOrder(v("title", "REQUIRED"), v("description", "REQUIRED"));
  }

  @Test
  void aTitleWithALineBreakOrTooLongIsRejectedButADescriptionMayHaveLineBreaks() {
    assertThat(violationsOf("a\nb", "d", null, null, null, null, null))
        .containsExactly(v("title", "INVALID_CHARACTERS"));
    assertThat(violationsOf("x".repeat(151), "d", null, null, null, null, null))
        .containsExactly(v("title", "TOO_LONG"));
    assertThat(violationsOf("t", "x".repeat(10_001), null, null, null, null, null))
        .containsExactly(v("description", "TOO_LONG"));
    assertThat(violationsOf("t", "a\u0000b", null, null, null, null, null))
        .containsExactly(v("description", "INVALID_CHARACTERS"));
  }

  @Test
  void anUnknownCountryIsRejected() {
    assertThat(violationsOf("t", "d", "ZZ", null, null, null, null))
        .containsExactly(v("country", "INVALID_COUNTRY"));
  }

  // BR-JOB-21: moneda, periodo y al menos un importe, todos o ninguno.
  @Test
  void aSalaryNeedsCurrencyAndPeriodAndAnAmount() {
    assertThat(violationsOf("t", "d", null, new BigDecimal("1000"), null, null, null))
        .containsExactlyInAnyOrder(v("salaryCurrency", "REQUIRED"), v("salaryPeriod", "REQUIRED"));
    assertThat(violationsOf("t", "d", null, null, null, "USD", SalaryPeriod.MONTH))
        .containsExactly(v("salaryMin", "REQUIRED"));
  }

  @Test
  void anInvertedOrNegativeOrHugeOrMessyRangeIsRejected() {
    assertThat(
            violationsOf(
                "t", "d", null, new BigDecimal("5"), new BigDecimal("4"), "USD", SalaryPeriod.YEAR))
        .containsExactly(v("salaryMax", "RANGE_INVERTED"));
    assertThat(violationsOf("t", "d", null, new BigDecimal("-1"), null, "USD", SalaryPeriod.YEAR))
        .containsExactly(v("salaryMin", "OUT_OF_RANGE"));
    assertThat(
            violationsOf(
                "t", "d", null, null, new BigDecimal("1000000000000"), "USD", SalaryPeriod.YEAR))
        .containsExactly(v("salaryMax", "OUT_OF_RANGE"));
    assertThat(
            violationsOf("t", "d", null, new BigDecimal("1.234"), null, "USD", SalaryPeriod.YEAR))
        .containsExactly(v("salaryMin", "TOO_MANY_DECIMALS"));
    assertThat(violationsOf("t", "d", null, BigDecimal.ONE, null, "XXQ", SalaryPeriod.YEAR))
        .containsExactly(v("salaryCurrency", "INVALID_CURRENCY"));
  }

  // Misma cifra con distinta escala es el mismo dato: guardarla otra vez no es un cambio.
  @Test
  void theSameAmountWithADifferentScaleIsTheSameSalary() {
    JobDetails a =
        JobDetails.of(
            "t", "d", null, null, new BigDecimal("4500"), null, "usd", SalaryPeriod.MONTH);
    JobDetails b =
        JobDetails.of(
            "t", "d", null, null, new BigDecimal("4500.00"), null, "USD", SalaryPeriod.MONTH);

    assertThat(a).isEqualTo(b);
  }

  @Test
  void aSingleEndedRangeIsAccepted() {
    JobDetails details =
        JobDetails.of("t", "d", null, null, null, new BigDecimal("9000"), "USD", SalaryPeriod.YEAR);

    assertThat(details.salary().min()).isNull();
    assertThat(details.salary().max()).isEqualByComparingTo("9000");
  }

  @Test
  void allViolationsAreReportedTogether() {
    assertThatThrownBy(() -> JobDetails.of("", "", null, "ZZ", null, null, "USD", null))
        .isInstanceOfSatisfying(
            ValidationFailedException.class,
            failure -> assertThat(failure.violations()).hasSizeGreaterThanOrEqualTo(4));
  }
}
