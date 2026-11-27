package com.usac.jobseeker.cv.application.usecase.checkapplicationreadiness;

import com.usac.jobseeker.common.domain.model.AccountId;
import java.util.Objects;

/**
 * La cuenta que quiere postular. Es una consulta interna para `ats`: la capacidad de candidato ya
 * la comprobó `ats` con el actor resuelto, y aquí solo se responde sobre los datos de esa cuenta.
 */
public record CheckApplicationReadinessQuery(AccountId accountId) {

  public CheckApplicationReadinessQuery {
    Objects.requireNonNull(accountId, "accountId");
  }
}
