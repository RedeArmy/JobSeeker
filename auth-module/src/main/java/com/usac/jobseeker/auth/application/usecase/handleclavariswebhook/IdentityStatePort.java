package com.usac.jobseeker.auth.application.usecase.handleclavariswebhook;

import com.usac.jobseeker.auth.domain.model.IdentityState;
import com.usac.jobseeker.auth.domain.model.IdentityStatus;
import com.usac.jobseeker.common.domain.model.AccountId;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Puerto de salida de HandleClavarisWebhook: el espejo local del estado de la identidad.
 *
 * <p>`applyIdentityEvent` debe ser atómico y decidir el orden con la regla de {@link
 * IdentityState}: dos eventos de la misma cuenta que llegan a la vez no pueden dejarla en un estado
 * que dependa de quién entre primero.
 */
public interface IdentityStatePort {

  boolean accountExists(AccountId accountId);

  /** El estado actual, o vacío si la cuenta no existe. */
  Optional<IdentityState> findIdentityState(AccountId accountId);

  /**
   * Aplica el evento si le corresponde.
   *
   * @return `true` si el estado cambió; `false` si el evento no era más reciente o la identidad ya
   *     estaba borrada
   */
  boolean applyIdentityEvent(
      AccountId accountId, IdentityStatus status, Instant occurredAt, UUID eventId);
}
