package com.usac.jobseeker.cv.application.usecase.savecandidateprofile;

import com.usac.jobseeker.common.domain.error.FieldViolation;
import com.usac.jobseeker.common.domain.error.ValidationFailedException;
import com.usac.jobseeker.common.domain.model.AccountId;
import com.usac.jobseeker.cv.domain.model.SalaryPeriod;
import com.usac.jobseeker.cv.domain.model.WorkModality;
import com.usac.jobseeker.cv.domain.model.WorkSchedule;
import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

/**
 * Guardar el perfil de candidato de la propia cuenta: el identificador sale del token, nunca del
 * cuerpo. La versión esperada es la que el cliente leyó; 0 significa «creo el perfil por primera
 * vez». La descubribilidad y la disponibilidad no viajan aquí: tienen sus propios casos de uso.
 */
public record SaveCandidateProfileCommand(
    AccountId accountId,
    String contactEmail,
    String contactPhone,
    String countryOfResidence,
    WorkModality desiredModality,
    WorkSchedule preferredWorkSchedule,
    Boolean relocationWillingness,
    BigDecimal salaryMin,
    BigDecimal salaryMax,
    String salaryCurrency,
    SalaryPeriod salaryPeriod,
    long expectedVersion) {

  public SaveCandidateProfileCommand {
    Objects.requireNonNull(accountId, "accountId");
    if (expectedVersion < 0) {
      throw new ValidationFailedException(List.of(new FieldViolation("version", "INVALID")));
    }
  }
}
