package com.usac.jobseeker.job.application.support;

import com.usac.jobseeker.job.domain.model.JobDetails;
import com.usac.jobseeker.job.domain.model.SalaryPeriod;
import com.usac.jobseeker.job.domain.model.WorkModality;
import java.math.BigDecimal;

/**
 * Los datos de la vacante tal como llegan, sin validar: los comparten crear y editar para que ambos
 * las construyan igual (BR-JOB-02, BR-JOB-21).
 */
public record JobDetailsInput(
    String title,
    String description,
    WorkModality modality,
    String country,
    BigDecimal salaryMin,
    BigDecimal salaryMax,
    String salaryCurrency,
    SalaryPeriod salaryPeriod) {

  /** Valida y construye; lanza `ValidationFailedException` con todas las violaciones. */
  public JobDetails toDetails() {
    return JobDetails.of(
        title, description, modality, country, salaryMin, salaryMax, salaryCurrency, salaryPeriod);
  }
}
