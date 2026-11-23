package com.usac.jobseeker.cv.application.usecase.manageprojects;

import com.usac.jobseeker.common.domain.model.AccountId;
import com.usac.jobseeker.cv.application.support.ProfileChange;
import java.util.Objects;
import java.util.UUID;

/** Quitar un elemento de proyectos. Solo se encuentra si es del perfil de quien llama. */
public record RemoveProjectCommand(AccountId accountId, long expectedVersion, UUID itemId) {

  public RemoveProjectCommand {
    Objects.requireNonNull(accountId, "accountId");
    Objects.requireNonNull(itemId, "itemId");
    ProfileChange.requireValidVersion(expectedVersion);
  }
}
