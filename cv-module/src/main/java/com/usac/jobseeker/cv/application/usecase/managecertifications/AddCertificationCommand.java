package com.usac.jobseeker.cv.application.usecase.managecertifications;

import com.usac.jobseeker.common.domain.model.AccountId;
import com.usac.jobseeker.cv.application.support.ProfileChange;
import java.time.LocalDate;
import java.util.Objects;

/**
 * Añadir un elemento a certificaciones. La cuenta sale del token, nunca del cuerpo. La versión es
 * la del perfil que el cliente leyó: si otra edición se guardó antes, se rechaza en vez de pisarla.
 */
public record AddCertificationCommand(
    AccountId accountId,
    long expectedVersion,
    String name,
    String issuingOrganization,
    LocalDate issueDate,
    LocalDate expirationDate,
    String credentialUrl) {

  public AddCertificationCommand {
    Objects.requireNonNull(accountId, "accountId");
    ProfileChange.requireValidVersion(expectedVersion);
  }
}
