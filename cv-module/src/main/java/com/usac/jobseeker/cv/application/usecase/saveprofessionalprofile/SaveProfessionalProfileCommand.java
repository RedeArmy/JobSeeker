package com.usac.jobseeker.cv.application.usecase.saveprofessionalprofile;

import com.usac.jobseeker.common.domain.error.FieldViolation;
import com.usac.jobseeker.common.domain.error.ValidationFailedException;
import com.usac.jobseeker.common.domain.model.AccountId;
import java.util.List;
import java.util.Objects;

/**
 * Guardar el perfil de la propia cuenta: el identificador sale del token, nunca del cuerpo. La
 * versión esperada es la que el cliente leyó; 0 significa «creo el perfil por primera vez».
 */
public record SaveProfessionalProfileCommand(
    AccountId accountId, String fullName, String headline, String bio, long expectedVersion) {

  public SaveProfessionalProfileCommand {
    Objects.requireNonNull(accountId, "accountId");
    if (expectedVersion < 0) {
      throw new ValidationFailedException(List.of(new FieldViolation("version", "INVALID")));
    }
  }
}
