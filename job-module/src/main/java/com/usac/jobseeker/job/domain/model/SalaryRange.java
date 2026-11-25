package com.usac.jobseeker.job.domain.model;

import com.usac.jobseeker.common.domain.error.FieldViolation;
import com.usac.jobseeker.common.domain.rules.SalaryRules;
import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

/**
 * El rango salarial de la vacante con su moneda y su periodo (BR-JOB-21). Un importe sin moneda y
 * periodo no es comparable con la expectativa del candidato (BR-MATCH-10), así que los cuatro datos
 * están todos o no está ninguno. Un solo valor se guarda con mínimo igual a máximo; también puede
 * darse solo un extremo.
 */
public record SalaryRange(BigDecimal min, BigDecimal max, String currency, SalaryPeriod period) {

  /** Los decimales de la columna `numeric(14,2)`. */
  private static final int SCALE = 2;

  public SalaryRange {
    Objects.requireNonNull(currency, "currency");
    Objects.requireNonNull(period, "period");
    if (min == null && max == null) {
      throw new IllegalArgumentException("hace falta al menos un importe");
    }
    // Escala fija de dos decimales, la de la columna: así 4500 y 4500.00 son el mismo dato y
    // guardar lo mismo no se confunde con un cambio (no sube la versión ni emite evento).
    min = SalaryRules.normalized(min);
    max = SalaryRules.normalized(max);
  }

  /**
   * Valida el conjunto y devuelve el rango, o `null` si no se dio ninguno de los cuatro datos.
   * Reúne todas las violaciones en la lista dada; la regla es la misma que usa la expectativa del
   * candidato (BR-JOB-21, BR-CAND-07) y vive en `common`.
   */
  static SalaryRange of(
      BigDecimal min,
      BigDecimal max,
      String currency,
      SalaryPeriod period,
      List<FieldViolation> violations) {
    SalaryRules.Checked checked = SalaryRules.check(min, max, currency, period != null, violations);
    return checked.absent() || !checked.usable()
        ? null
        : new SalaryRange(min, max, checked.currency(), period);
  }
}
