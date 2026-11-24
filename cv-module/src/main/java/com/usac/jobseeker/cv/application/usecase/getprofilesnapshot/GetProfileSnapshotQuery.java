package com.usac.jobseeker.cv.application.usecase.getprofilesnapshot;

import com.usac.jobseeker.common.domain.model.AccountId;
import java.util.Objects;

/** Qué cuenta se quiere leer. Es una consulta interna entre módulos: no hay actor que autorizar. */
public record GetProfileSnapshotQuery(AccountId accountId) {

  public GetProfileSnapshotQuery {
    Objects.requireNonNull(accountId, "accountId");
  }
}
