package com.usac.jobseeker.auth.infrastructure.adapter.out.persistence;

import com.usac.jobseeker.auth.application.usecase.handleclavariswebhook.IdentityStatePort;
import com.usac.jobseeker.auth.domain.model.IdentityState;
import com.usac.jobseeker.auth.domain.model.IdentityStatus;
import com.usac.jobseeker.common.application.account.AccountChangeObserver;
import com.usac.jobseeker.common.domain.model.AccountId;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Adaptador JDBC del espejo del estado de la identidad (columnas `identity_*` de `accounts`).
 *
 * <p>La regla de orden de {@link IdentityState} se escribe aquí en SQL, en un único `UPDATE`
 * condicional: el bloqueo de la fila serializa los eventos concurrentes de una misma cuenta, así
 * que el resultado no depende de quién llegue primero. Un contrato de pruebas comprueba que el
 * dominio y este SQL deciden igual.
 */
public class JdbcIdentityStateRepository implements IdentityStatePort {

  private final JdbcClient jdbc;
  private final AccountChangeObserver observer;

  public JdbcIdentityStateRepository(JdbcClient jdbc) {
    this(jdbc, AccountChangeObserver.NONE);
  }

  public JdbcIdentityStateRepository(JdbcClient jdbc, AccountChangeObserver observer) {
    this.jdbc = jdbc;
    this.observer = observer;
  }

  @Override
  public boolean accountExists(AccountId accountId) {
    return jdbc.sql("select 1 from accounts where id = :id")
        .param("id", accountId.value())
        .query(Integer.class)
        .optional()
        .isPresent();
  }

  @Override
  public Optional<IdentityState> findIdentityState(AccountId accountId) {
    return jdbc.sql(
            "select identity_status, identity_event_at, identity_event_id"
                + " from accounts where id = :id")
        .param("id", accountId.value())
        .query(
            (rows, n) -> {
              OffsetDateTime at = rows.getObject("identity_event_at", OffsetDateTime.class);
              return new IdentityState(
                  IdentityStatus.valueOf(rows.getString("identity_status")),
                  at == null ? null : at.toInstant(),
                  rows.getObject("identity_event_id", UUID.class));
            })
        .optional();
  }

  // Una identidad borrada no cambia nunca; DELETED se aplica siempre; el resto, solo si la tupla
  // `(occurredAt, event_id)` es mayor que la del último evento. Postgres compara los uuid byte a
  // byte sin signo, como IdentityState.compareUnsigned.
  @Override
  public boolean applyIdentityEvent(
      AccountId accountId, IdentityStatus status, Instant occurredAt, UUID eventId) {
    int updated =
        jdbc.sql(
                "update accounts set identity_status = :status, identity_event_at = :at,"
                    + " identity_event_id = :eventId"
                    + " where id = :id and identity_status <> 'DELETED'"
                    + " and (:status = 'DELETED' or identity_event_at is null"
                    + " or (identity_event_at, identity_event_id) < (:at, :eventId))")
            .param("id", accountId.value())
            .param("status", status.name())
            .param("at", OffsetDateTime.ofInstant(occurredAt, ZoneOffset.UTC))
            .param("eventId", eventId)
            .update();
    boolean applied = updated == 1;
    if (applied) {
      observer.accountChanged(accountId);
    }
    return applied;
  }
}
