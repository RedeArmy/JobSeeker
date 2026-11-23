package com.usac.jobseeker.cv.application.usecase.managecertifications;

import com.usac.jobseeker.common.domain.model.AccountId;
import com.usac.jobseeker.cv.application.support.ProfileChange;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/** Reemplazar un elemento de certificaciones. Solo se encuentra si es del perfil de quien llama. */
public record UpdateCertificationCommand(
    AccountId accountId,
    long expectedVersion,
    UUID itemId,
    String name,
    String issuingOrganization,
    LocalDate issueDate,
    LocalDate expirationDate,
    String credentialUrl) {

  public UpdateCertificationCommand {
    Objects.requireNonNull(accountId, "accountId");
    Objects.requireNonNull(itemId, "itemId");
    ProfileChange.requireValidVersion(expectedVersion);
  }
}
