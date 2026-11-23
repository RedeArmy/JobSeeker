package com.usac.jobseeker.cv.application.usecase.manageexperiences;

import com.usac.jobseeker.common.domain.model.AccountId;
import com.usac.jobseeker.cv.application.support.ProfileChange;
import java.time.LocalDate;
import java.util.Objects;

/**
 * Añadir un elemento a experiencias laborales. La cuenta sale del token, nunca del cuerpo. La
 * versión es la del perfil que el cliente leyó: si otra edición se guardó antes, se rechaza en vez
 * de pisarla.
 */
public record AddExperienceCommand(
    AccountId accountId,
    long expectedVersion,
    String company,
    String title,
    LocalDate startDate,
    LocalDate endDate,
    String description) {

  public AddExperienceCommand {
    Objects.requireNonNull(accountId, "accountId");
    ProfileChange.requireValidVersion(expectedVersion);
  }
}
