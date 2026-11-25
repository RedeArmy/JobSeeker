package com.usac.jobseeker.common.domain.rules;

import static org.assertj.core.api.Assertions.assertThat;

import com.usac.jobseeker.common.domain.error.FieldViolation;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** La regla única del dinero con unidad (BR-CAND-07, BR-JOB-21), probada una sola vez aquí. */
class SalaryRulesTest {

  private final List<FieldViolation> violations = new ArrayList<>();

  private SalaryRules.Checked check(String min, String max, String currency, boolean hasPeriod) {
    return SalaryRules.check(
        min == null ? null : new BigDecimal(min),
        max == null ? null : new BigDecimal(max),
        currency,
        hasPeriod,
        violations);
  }

  private static FieldViolation v(String field, String code) {
    return new FieldViolation(field, code);
  }

  @Test
  void nothingGivenIsAbsentNotInvalid() {
    SalaryRules.Checked checked = check(null, null, null, false);

    assertThat(checked.absent()).isTrue();
    assertThat(checked.usable()).isFalse();
    assertThat(violations).isEmpty();
  }

  @Test
  void aCompleteSalaryIsUsableWithTheNormalizedCurrency() {
    SalaryRules.Checked checked = check("3000", "4500", "usd", true);

    assertThat(checked.usable()).isTrue();
    assertThat(checked.currency()).isEqualTo("USD");
    assertThat(violations).isEmpty();
  }

  @Test
  void aSingleEndedRangeIsUsable() {
    assertThat(check("3000", null, "USD", true).usable()).isTrue();
    assertThat(check(null, "9000", "USD", true).usable()).isTrue();
  }

  // Un importe sin unidad no es comparable: moneda, periodo e importe, todos o ninguno.
  @Test
  void anyPartWithoutTheOthersIsReportedPieceByPiece() {
    check("1000", null, null, false);
    assertThat(violations)
        .containsExactlyInAnyOrder(v("salaryCurrency", "REQUIRED"), v("salaryPeriod", "REQUIRED"));

    violations.clear();
    check(null, null, "USD", true);
    assertThat(violations).containsExactly(v("salaryMin", "REQUIRED"));

    violations.clear();
    check(null, null, null, true);
    assertThat(violations)
        .containsExactlyInAnyOrder(v("salaryMin", "REQUIRED"), v("salaryCurrency", "REQUIRED"));
  }

  @Test
  void invalidAmountsAreRejectedWithTheirOwnCodes() {
    assertThat(check("5", "4", "USD", true).usable()).isFalse();
    assertThat(violations).containsExactly(v("salaryMax", "RANGE_INVERTED"));

    violations.clear();
    check("-1", null, "USD", true);
    check(null, "1000000000000", "USD", true);
    check("1.234", null, "USD", true);
    assertThat(violations)
        .containsExactly(
            v("salaryMin", "OUT_OF_RANGE"),
            v("salaryMax", "OUT_OF_RANGE"),
            v("salaryMin", "TOO_MANY_DECIMALS"));
  }

  @Test
  void anUnknownCurrencyMakesItUnusable() {
    SalaryRules.Checked checked = check("1", null, "XXQ", true);

    assertThat(checked.usable()).isFalse();
    assertThat(violations).containsExactly(v("salaryCurrency", "INVALID_CURRENCY"));
  }

  @Test
  void trailingZerosBeyondTheScaleAreNotMessyDecimals() {
    assertThat(check("1.2300", null, "USD", true).usable()).isTrue();
    assertThat(violations).isEmpty();
  }

  @Test
  void theAmountBoundaryIsTheColumnLimit() {
    assertThat(check("999999999999.99", null, "USD", true).usable()).isTrue();
    assertThat(violations).isEmpty();
  }

  // 4500 y 4500.00 son el mismo dato: guardarlo otra vez no es un cambio.
  @Test
  void normalizedFixesTheScaleAndKeepsNull() {
    assertThat(SalaryRules.normalized(new BigDecimal("4500"))).isEqualTo(new BigDecimal("4500.00"));
    assertThat(SalaryRules.normalized(new BigDecimal("4500.00")))
        .isEqualTo(SalaryRules.normalized(new BigDecimal("4500")));
    assertThat(SalaryRules.normalized(null)).isNull();
  }
}
