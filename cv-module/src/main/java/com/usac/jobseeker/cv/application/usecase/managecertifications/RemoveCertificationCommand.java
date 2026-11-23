package com.usac.jobseeker.cv.application.usecase.managecertifications;

import com.usac.jobseeker.common.domain.model.AccountId;
import com.usac.jobseeker.cv.application.support.ProfileChange;
import java.util.Objects;
import java.util.UUID;

/** Quitar un elemento de certificaciones. Solo se encuentra si es del perfil de quien llama. */
public record RemoveCertificationCommand(AccountId accountId, long expectedVersion, UUID itemId) {

  public RemoveCertificationCommand {
    Objects.requireNonNull(accountId, "accountId");
    Objects.requireNonNull(itemId, "itemId");
    ProfileChange.requireValidVersion(expectedVersion);
  }
}
