package com.usac.jobseeker.auth.infrastructure.adapter.out.persistence;

import com.usac.jobseeker.auth.application.usecase.handleclavariswebhook.IdentityStateContract;
import com.usac.jobseeker.auth.application.usecase.handleclavariswebhook.IdentityStatePort;
import com.usac.jobseeker.auth.infrastructure.AuthPostgres;
import com.usac.jobseeker.common.domain.model.AccountId;
import com.usac.jobseeker.common.domain.model.Capability;
import java.util.EnumSet;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * El adaptador JDBC cumple el mismo contrato que el almacén en memoria, contra la tabla real de
 * Postgres 17 con la migración del módulo: el SQL decide el orden igual que el dominio.
 */
class JdbcIdentityStateRepositoryContractTest extends IdentityStateContract {

  private final JdbcClient jdbc = JdbcClient.create(AuthPostgres.dataSource());
  private final JdbcIdentityStateRepository repository = new JdbcIdentityStateRepository(jdbc);
  private final JdbcAccountRepository accounts = new JdbcAccountRepository(jdbc);

  @Override
  protected IdentityStatePort port() {
    return repository;
  }

  @Override
  protected AccountId newAccount() {
    AccountId id = new AccountId(UUID.randomUUID());
    accounts.insertIfAbsent(id, EnumSet.of(Capability.CANDIDATE));
    return id;
  }
}
