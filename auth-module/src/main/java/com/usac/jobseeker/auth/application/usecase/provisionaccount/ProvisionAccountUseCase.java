package com.usac.jobseeker.auth.application.usecase.provisionaccount;

/**
 * Aprovisionamiento perezoso de la cuenta local en el primer login (ADR-0020, BR-ACCT-09). Es
 * idempotente: un segundo login no cambia nada.
 */
public interface ProvisionAccountUseCase {

  ProvisionAccountResult execute(ProvisionAccountCommand command);
}
