package com.usac.jobseeker.auth.infrastructure.adapter.out.persistence;

import com.usac.jobseeker.auth.application.usecase.getmyaccount.MyAccountPort;
import com.usac.jobseeker.auth.application.usecase.provisionaccount.AccountProvisioningPort;
import com.usac.jobseeker.auth.application.usecase.resolveactor.ActorLookupPort;
import com.usac.jobseeker.auth.domain.model.Account;
import com.usac.jobseeker.auth.domain.model.DeletionStatus;
import com.usac.jobseeker.auth.domain.model.IdentityStatus;
import com.usac.jobseeker.common.application.account.AccountChangeObserver;
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
public class JdbcAccountRepository
    implements AccountProvisioningPort, ActorLookupPort, MyAccountPort {

  private final JdbcClient jdbc;
  private final AccountChangeObserver observer;

  public JdbcAccountRepository(JdbcClient jdbc) {
    this(jdbc, AccountChangeObserver.NONE);
  }

  public JdbcAccountRepository(JdbcClient jdbc, AccountChangeObserver observer) {
    this.jdbc = jdbc;
    this.observer = observer;
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
    boolean created = inserted == 1;
    if (created) {
      observer.accountChanged(id);
    }
    return created;
  }

  @Override
  public Optional<Account> findById(AccountId id) {
    return Optional.ofNullable(findAllById(java.util.List.of(id)).get(id));
  }

  // Una sola consulta para varias cuentas (TD-039); la individual es el caso de una.
  @Override
  public java.util.Map<AccountId, Account> findAllById(java.util.Collection<AccountId> ids) {
    if (ids.isEmpty()) {
      return java.util.Map.of();
    }
    java.util.Map<AccountId, Account> found = new java.util.HashMap<>();
    jdbc.sql(
            "select id, is_candidate_enabled, is_recruiter_enabled, deletion_status, identity_status"
                + " from accounts where id = any(:ids)")
        .param("ids", ids.stream().map(AccountId::value).toArray(java.util.UUID[]::new))
        .query(
            (rows, n) -> {
              Set<Capability> capabilities = EnumSet.noneOf(Capability.class);
              if (rows.getBoolean("is_candidate_enabled")) {
                capabilities.add(Capability.CANDIDATE);
              }
              if (rows.getBoolean("is_recruiter_enabled")) {
                capabilities.add(Capability.RECRUITER);
              }
              AccountId id = new AccountId(rows.getObject("id", java.util.UUID.class));
              found.put(
                  id,
                  new Account(
                      id,
                      capabilities,
                      DeletionStatus.valueOf(rows.getString("deletion_status")),
                      IdentityStatus.valueOf(rows.getString("identity_status"))));
              return id;
            })
        .list();
    return found;
  }
}
