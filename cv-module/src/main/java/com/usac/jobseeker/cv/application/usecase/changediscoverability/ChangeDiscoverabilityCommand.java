package com.usac.jobseeker.cv.application.usecase.changediscoverability;

import com.usac.jobseeker.common.domain.model.AccountId;
import com.usac.jobseeker.cv.application.support.CandidateChange;
import java.util.Objects;

/**
 * Activar o desactivar que el perfil aparezca en las sugerencias a reclutadores (BR-DATA-03). El
 * identificador sale del token; la versión es la del perfil de candidato que el cliente leyó.
 */
public record ChangeDiscoverabilityCommand(
    AccountId accountId, boolean discoverable, long expectedVersion) {

  public ChangeDiscoverabilityCommand {
    Objects.requireNonNull(accountId, "accountId");
    CandidateChange.requireValidVersion(expectedVersion);
  }
}
