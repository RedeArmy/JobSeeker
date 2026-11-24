package com.usac.jobseeker.cv.domain.model;

/**
 * El periodo al que se refiere un importe de salario. Sin él, «3 000» no se puede comparar con una
 * vacante anual (BR-MATCH-10).
 */
public enum SalaryPeriod {
  MONTH,
  YEAR
}
