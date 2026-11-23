package com.usac.jobseeker.cv.application.usecase.manageexperiences;

import com.usac.jobseeker.common.domain.model.AccountId;
import com.usac.jobseeker.cv.application.support.ProfileChange;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/**
 * Reemplazar un elemento de experiencias laborales. Solo se encuentra si es del perfil de quien
 * llama.
 */
public record UpdateExperienceCommand(
    AccountId accountId,
    long expectedVersion,
    UUID itemId,
    String company,
    String title,
    LocalDate startDate,
    LocalDate endDate,
    String description) {

  public UpdateExperienceCommand {
    Objects.requireNonNull(accountId, "accountId");
    Objects.requireNonNull(itemId, "itemId");
    ProfileChange.requireValidVersion(expectedVersion);
  }
}
