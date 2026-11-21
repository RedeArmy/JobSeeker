package com.usac.jobseeker.auth.infrastructure.adapter.out.persistence;

import com.usac.jobseeker.auth.application.usecase.provisionaccount.AccountProvisioningPort;
import com.usac.jobseeker.auth.application.usecase.resolveactor.ActorLookupPort;
import com.usac.jobseeker.auth.domain.model.Account;
import com.usac.jobseeker.auth.domain.model.DeletionStatus;
import com.usac.jobseeker.common.domain.model.AccountId;
import com.usac.jobseeker.common.domain.model.Capability;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Adaptador JDBC de la tabla `accounts` (data-model.md §4.1); implementa los puertos de los dos
 * casos de uso.
 */
public class JdbcAccountRepository implements AccountProvisioningPort, ActorLookupPort {

  private final JdbcClient jdbc;

  public JdbcAccountRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  // ON CONFLICT DO NOTHING hace atómico el primer login: dos peticiones simultáneas de la misma
  // identidad no chocan ni crean dos filas; solo una ve `1` fila afectada.
  @Override
  public boolean insertIfAbsent(AccountId id, Set<Capability> capabilities) {
    int inserted =
        jdbc.sql(
                "insert into accounts (id, is_candidate_enabled, is_recruiter_enabled)"
                    + " values (:id, :candidate, :recruiter) on conflict (id) do nothing")
            .param("id", id.value())
            .param("candidate", capabilities.contains(Capability.CANDIDATE))
            .param("recruiter", capabilities.contains(Capability.RECRUITER))
            .update();
    return inserted == 1;
  }

  @Override
  public Optional<Account> findById(AccountId id) {
    return jdbc.sql(
            "select id, is_candidate_enabled, is_recruiter_enabled, deletion_status"
                + " from accounts where id = :id")
        .param("id", id.value())
        .query(
            (rows, n) -> {
              Set<Capability> capabilities = EnumSet.noneOf(Capability.class);
              if (rows.getBoolean("is_candidate_enabled")) {
                capabilities.add(Capability.CANDIDATE);
              }
              if (rows.getBoolean("is_recruiter_enabled")) {
                capabilities.add(Capability.RECRUITER);
              }
              return new Account(
                  new AccountId(rows.getObject("id", java.util.UUID.class)),
                  capabilities,
                  DeletionStatus.valueOf(rows.getString("deletion_status")));
            })
        .optional();
  }
}
