package com.usac.jobseeker.cv.domain.model;

import com.usac.jobseeker.common.domain.error.FieldViolation;
import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

/**
 * La expectativa salarial: un rango con su moneda y su periodo (BR-CAND-07). Un importe sin moneda
 * y periodo no es comparable con el de una vacante (BR-MATCH-10), así que los cuatro datos están
 * todos o no está ninguno. Un solo valor se guarda con mínimo igual a máximo; también puede darse
 * solo un extremo («desde 3 000»).
 */
public record SalaryExpectation(
    BigDecimal min, BigDecimal max, String currency, SalaryPeriod period) {

  /** El mayor importe que cabe en la columna `numeric(14,2)`. */
  static final BigDecimal LIMIT = new BigDecimal("999999999999.99");

  public SalaryExpectation {
    Objects.requireNonNull(currency, "currency");
    Objects.requireNonNull(period, "period");
    if (min == null && max == null) {
      throw new IllegalArgumentException("hace falta al menos un importe");
    }
    // Escala fija de dos decimales, la de la columna: así 4500 y 4500.00 son el mismo dato y
    // guardar
    // lo mismo no se confunde con un cambio (no sube la versión ni emite evento, BR-MATCH-03).
    min = twoDecimals(min);
    max = twoDecimals(max);
  }

  /**
   * Valida el conjunto y devuelve la expectativa, o `null` si no se dio ninguno de los cuatro
   * datos. Reúne todas las violaciones en la lista dada.
   */
  static SalaryExpectation of(
      BigDecimal min,
      BigDecimal max,
      String currency,
      SalaryPeriod period,
      List<FieldViolation> violations) {
    String code = CandidateRules.currency("salaryCurrency", currency, violations);
    boolean any = min != null || max != null || code != null || period != null;
    if (!any) {
      return null;
    }
    boolean ok = true;
    if (min == null && max == null) {
      violations.add(new FieldViolation("salaryMin", CandidateRules.REQUIRED));
      ok = false;
    }
    if (code == null && (currency == null || currency.isBlank())) {
      violations.add(new FieldViolation("salaryCurrency", CandidateRules.REQUIRED));
      ok = false;
    }
    if (period == null) {
      violations.add(new FieldViolation("salaryPeriod", CandidateRules.REQUIRED));
      ok = false;
    }
    ok &= amount("salaryMin", min, violations);
    ok &= amount("salaryMax", max, violations);
    if (min != null && max != null && min.compareTo(max) > 0) {
      violations.add(new FieldViolation("salaryMax", "RANGE_INVERTED"));
      ok = false;
    }
    return ok && code != null ? new SalaryExpectation(min, max, code, period) : null;
  }

  private static BigDecimal twoDecimals(BigDecimal value) {
    return value == null ? value : value.setScale(2, java.math.RoundingMode.UNNECESSARY);
  }

  private static boolean amount(String field, BigDecimal value, List<FieldViolation> violations) {
    if (value == null) {
      return true;
    }
    if (value.signum() < 0 || value.compareTo(LIMIT) > 0) {
      violations.add(new FieldViolation(field, "OUT_OF_RANGE"));
      return false;
    }
    if (value.scale() > 2 && value.stripTrailingZeros().scale() > 2) {
      violations.add(new FieldViolation(field, "TOO_MANY_DECIMALS"));
      return false;
    }
    return true;
  }
}
