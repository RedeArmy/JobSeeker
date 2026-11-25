package com.usac.jobseeker.cv.domain.model;

import com.usac.jobseeker.common.domain.error.FieldViolation;
import com.usac.jobseeker.common.domain.rules.SalaryRules;
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

  public SalaryExpectation {
    Objects.requireNonNull(currency, "currency");
    Objects.requireNonNull(period, "period");
    if (min == null && max == null) {
      throw new IllegalArgumentException("hace falta al menos un importe");
    }
    // Escala fija de dos decimales, la de la columna: así 4500 y 4500.00 son el mismo dato y
    // guardar
    // lo mismo no se confunde con un cambio (no sube la versión ni emite evento, BR-MATCH-03).
    min = SalaryRules.normalized(min);
    max = SalaryRules.normalized(max);
  }

  /**
   * Valida el conjunto y devuelve la expectativa, o `null` si no se dio ninguno de los cuatro
   * datos. Reúne todas las violaciones en la lista dada; la regla es la misma que usa la vacante
   * (BR-CAND-07, BR-JOB-21) y vive en `common`.
   */
  static SalaryExpectation of(
      BigDecimal min,
      BigDecimal max,
      String currency,
      SalaryPeriod period,
      List<FieldViolation> violations) {
    SalaryRules.Checked checked = SalaryRules.check(min, max, currency, period != null, violations);
    return checked.absent() || !checked.usable()
        ? null
        : new SalaryExpectation(min, max, checked.currency(), period);
  }
}
