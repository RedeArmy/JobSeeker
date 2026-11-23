package com.usac.jobseeker.cv.application.usecase.manageeducation;

import com.usac.jobseeker.common.domain.model.AccountId;
import com.usac.jobseeker.cv.application.support.ProfileChange;
import com.usac.jobseeker.cv.domain.model.EducationLevel;
import java.time.LocalDate;
import java.util.Objects;

/**
 * Añadir un elemento a formaciones académicas. La cuenta sale del token, nunca del cuerpo. La
 * versión es la del perfil que el cliente leyó: si otra edición se guardó antes, se rechaza en vez
 * de pisarla.
 */
public record AddEducationCommand(
    AccountId accountId,
    long expectedVersion,
    String institution,
    String degree,
    EducationLevel level,
    LocalDate completionDate) {

  public AddEducationCommand {
    Objects.requireNonNull(accountId, "accountId");
    ProfileChange.requireValidVersion(expectedVersion);
  }
}
