package com.usac.jobseeker.cv.application.usecase.changeavailability;

import com.usac.jobseeker.common.domain.error.FieldViolation;
import com.usac.jobseeker.common.domain.error.ValidationFailedException;
import com.usac.jobseeker.common.domain.model.AccountId;
import com.usac.jobseeker.cv.application.support.CandidateChange;
import com.usac.jobseeker.cv.domain.model.AvailabilityStatus;
import java.util.List;
import java.util.Objects;

/**
 * Declarar el estado de disponibilidad (BR-CAND-04). Volver a `UNSET` es válido: es retirar la
 * declaración. Un estado ausente no lo es: sería un olvido, no una decisión.
 */
public record ChangeAvailabilityCommand(
    AccountId accountId, AvailabilityStatus status, long expectedVersion) {

  public ChangeAvailabilityCommand {
    Objects.requireNonNull(accountId, "accountId");
    if (status == null) {
      throw new ValidationFailedException(List.of(new FieldViolation("status", "REQUIRED")));
    }
    CandidateChange.requireValidVersion(expectedVersion);
  }
}
