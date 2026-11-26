package com.usac.jobseeker.cv.application.usecase.getprofilesnapshot;

import com.usac.jobseeker.common.domain.model.AccountId;
import java.util.List;
import java.util.Objects;

/**
 * Qué cuentas se quieren leer **de una vez**. Es una consulta interna entre módulos: no hay actor
 * que autorizar. El tope evita construir consultas de tamaño arbitrario; quien necesite más lo pide
 * en tandas.
 */
public record GetProfileSnapshotsQuery(List<AccountId> accountIds) {

  /** El máximo de cuentas por consulta: sobra para los 200 recuperados de los sugeridos. */
  public static final int MAX_ACCOUNTS = 500;

  public GetProfileSnapshotsQuery {
    Objects.requireNonNull(accountIds, "accountIds");
    accountIds = List.copyOf(accountIds);
    if (accountIds.size() > MAX_ACCOUNTS) {
      throw new IllegalArgumentException("como máximo " + MAX_ACCOUNTS + " cuentas por consulta");
    }
  }
}
