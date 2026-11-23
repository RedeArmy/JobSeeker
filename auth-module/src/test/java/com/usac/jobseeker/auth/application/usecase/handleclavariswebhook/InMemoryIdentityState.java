package com.usac.jobseeker.auth.application.usecase.handleclavariswebhook;

import com.usac.jobseeker.auth.domain.model.IdentityState;
import com.usac.jobseeker.auth.domain.model.IdentityStatus;
import com.usac.jobseeker.common.domain.model.AccountId;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Espejo de identidad en memoria para probar el caso de uso sin base de datos. */
public class InMemoryIdentityState implements IdentityStatePort {

  private final Map<AccountId, IdentityState> states = new ConcurrentHashMap<>();

  public void create(AccountId accountId) {
    states.putIfAbsent(accountId, IdentityState.initial());
  }

  @Override
  public boolean accountExists(AccountId accountId) {
    return states.containsKey(accountId);
  }

  @Override
  public Optional<IdentityState> findIdentityState(AccountId accountId) {
    return Optional.ofNullable(states.get(accountId));
  }

  @Override
  public boolean applyIdentityEvent(
      AccountId accountId, IdentityStatus status, Instant occurredAt, UUID eventId) {
    boolean[] changed = {false};
    states.computeIfPresent(
        accountId,
        (id, current) ->
            current
                .accept(status, occurredAt, eventId)
                .map(
                    next -> {
                      changed[0] = true;
                      return next;
                    })
                .orElse(current));
    return changed[0];
  }
}
