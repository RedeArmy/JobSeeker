package com.usac.jobseeker.job.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/** El rango salarial siempre lleva moneda y periodo, y al menos un importe (BR-JOB-21). */
class SalaryRangeTest {

  @Test
  void aRangeKeepsTheTwoDecimalsOfTheColumn() {
    var range = new SalaryRange(new BigDecimal("3000"), null, "USD", SalaryPeriod.MONTH);

    assertThat(range.min()).isEqualTo(new BigDecimal("3000.00"));
    assertThat(range.max()).isNull();
  }

  @Test
  void aRangeWithoutAnyAmountCannotExist() {
    assertThatThrownBy(() -> new SalaryRange(null, null, "USD", SalaryPeriod.MONTH))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void aRangeWithoutCurrencyOrPeriodCannotExist() {
    assertThatThrownBy(() -> new SalaryRange(BigDecimal.ONE, null, null, SalaryPeriod.MONTH))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> new SalaryRange(BigDecimal.ONE, null, "USD", null))
        .isInstanceOf(NullPointerException.class);
  }
}
