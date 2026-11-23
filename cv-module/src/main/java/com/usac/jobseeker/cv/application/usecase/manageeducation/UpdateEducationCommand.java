package com.usac.jobseeker.cv.application.usecase.manageeducation;

import com.usac.jobseeker.common.domain.model.AccountId;
import com.usac.jobseeker.cv.application.support.ProfileChange;
import com.usac.jobseeker.cv.domain.model.EducationLevel;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/**
 * Reemplazar un elemento de formaciones académicas. Solo se encuentra si es del perfil de quien
 * llama.
 */
public record UpdateEducationCommand(
    AccountId accountId,
    long expectedVersion,
    UUID itemId,
    String institution,
    String degree,
    EducationLevel level,
    LocalDate completionDate) {

  public UpdateEducationCommand {
    Objects.requireNonNull(accountId, "accountId");
    Objects.requireNonNull(itemId, "itemId");
    ProfileChange.requireValidVersion(expectedVersion);
  }
}
