package com.usac.jobseeker.auth.infrastructure.adapter.out.persistence;

import com.usac.jobseeker.auth.application.usecase.AccountStoreContract;
import com.usac.jobseeker.auth.application.usecase.provisionaccount.AccountProvisioningPort;
import com.usac.jobseeker.auth.application.usecase.resolveactor.ActorLookupPort;
import com.usac.jobseeker.auth.infrastructure.AuthPostgres;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * El adaptador JDBC cumple el mismo contrato que el almacén en memoria, contra la tabla real que
 * crea la migración del propio módulo en Postgres 17 (ADR-0028).
 */
class JdbcAccountRepositoryContractTest extends AccountStoreContract {

  private final JdbcAccountRepository repository =
      new JdbcAccountRepository(JdbcClient.create(AuthPostgres.dataSource()));

  @Override
  protected AccountProvisioningPort provisioning() {
    return repository;
  }

  @Override
  protected ActorLookupPort lookup() {
    return repository;
  }
}
