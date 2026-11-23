package com.usac.jobseeker.cv.application.usecase.manageeducation;

import com.usac.jobseeker.common.domain.model.AccountId;
import com.usac.jobseeker.cv.application.support.ProfileChange;
import java.util.Objects;
import java.util.UUID;

/**
 * Quitar un elemento de formaciones académicas. Solo se encuentra si es del perfil de quien llama.
 */
public record RemoveEducationCommand(AccountId accountId, long expectedVersion, UUID itemId) {

  public RemoveEducationCommand {
    Objects.requireNonNull(accountId, "accountId");
    Objects.requireNonNull(itemId, "itemId");
    ProfileChange.requireValidVersion(expectedVersion);
  }
}
