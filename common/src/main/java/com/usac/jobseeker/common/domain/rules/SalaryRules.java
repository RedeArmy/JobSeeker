package com.usac.jobseeker.common.domain.rules;

import com.usac.jobseeker.common.domain.error.FieldViolation;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * La regla del dinero con unidad que comparten la expectativa del candidato y el rango de la
 * vacante (BR-CAND-07, BR-JOB-21): moneda, periodo y al menos un importe están **todos presentes o
 * todos ausentes**, porque un importe sin unidad no es comparable (BR-MATCH-10). Un solo valor se
 * guarda con mínimo igual a máximo; también puede darse solo un extremo.
 *
 * <p>Cada módulo conserva su propio registro y su propio enumerado de periodo (ARCH-04: un módulo
 * no importa tipos de otro); lo único que se comparte es la validación.
 */
public final class SalaryRules {

  /** El mayor importe que cabe en una columna `numeric(14,2)`. */
  static final BigDecimal LIMIT = new BigDecimal("999999999999.99");

  /** Los decimales de la columna. */
  public static final int SCALE = 2;

  private static final String SALARY_MIN = "salaryMin";
  private static final String SALARY_MAX = "salaryMax";

  private SalaryRules() {}

  /**
   * El resultado de revisar un salario: si no se dio ningún dato, si lo dado es válido, y la moneda
   * ya normalizada.
   */
  public record Checked(boolean absent, boolean valid, String currency) {

    /** Cuando hay datos y todos son válidos, el llamador construye su registro con `currency`. */
    public boolean usable() {
      return !absent && valid && currency != null;
    }
  }

  /** Revisa el conjunto y reúne todas las violaciones en la lista dada. */
  public static Checked check(
      BigDecimal min,
      BigDecimal max,
      String currency,
      boolean hasPeriod,
      List<FieldViolation> violations) {
    int before = violations.size();
    String code = FormatRules.currency("salaryCurrency", currency, violations);
    // Una moneda que no existe ya agregó su violación: el conjunto no es utilizable aunque lo demás
    // sí.
    boolean ok = violations.size() == before;
    boolean any = min != null || max != null || code != null || hasPeriod;
    if (!any) {
      return new Checked(true, true, null);
    }
    if (min == null && max == null) {
      violations.add(new FieldViolation(SALARY_MIN, FormatRules.REQUIRED));
      ok = false;
    }
    if (code == null && (currency == null || currency.isBlank())) {
      violations.add(new FieldViolation("salaryCurrency", FormatRules.REQUIRED));
      ok = false;
    }
    if (!hasPeriod) {
      violations.add(new FieldViolation("salaryPeriod", FormatRules.REQUIRED));
      ok = false;
    }
    ok &= amount(SALARY_MIN, min, violations);
    ok &= amount(SALARY_MAX, max, violations);
    if (min != null && max != null && min.compareTo(max) > 0) {
      violations.add(new FieldViolation(SALARY_MAX, "RANGE_INVERTED"));
      ok = false;
    }
    return new Checked(false, ok, code);
  }

  /**
   * Escala fija de dos decimales, la de la columna: así 4500 y 4500.00 son el mismo dato y guardar
   * lo mismo no se confunde con un cambio (no sube la versión ni emite evento, BR-MATCH-03).
   */
  public static BigDecimal normalized(BigDecimal value) {
    return value == null ? null : value.setScale(SCALE, RoundingMode.UNNECESSARY);
  }

  private static boolean amount(String field, BigDecimal value, List<FieldViolation> violations) {
    if (value == null) {
      return true;
    }
    if (value.signum() < 0 || value.compareTo(LIMIT) > 0) {
      violations.add(new FieldViolation(field, FormatRules.OUT_OF_RANGE));
      return false;
    }
    if (value.stripTrailingZeros().scale() > SCALE) {
      violations.add(new FieldViolation(field, "TOO_MANY_DECIMALS"));
      return false;
    }
    return true;
  }
}
