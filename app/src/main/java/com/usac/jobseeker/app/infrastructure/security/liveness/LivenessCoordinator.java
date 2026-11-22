package com.usac.jobseeker.app.infrastructure.security.liveness;

import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Decide si una sesión sigue viva (ADR-0020, reglas 1, 2, 5, 7, 8 y 12). Renueva el refresh token
 * como mucho cada {@code renewalInterval} por sesión, siempre dentro de un cerrojo y releyendo el
 * estado de Redis ya dentro de él.
 *
 * <p>Por qué el cerrojo: los refresh tokens de Clavaris son de un solo uso y reutilizar uno revoca
 * toda la familia; con 8 peticiones simultáneas sin cerrojo, 7 fallaron. Por qué releer dentro:
 * quien espera el cerrojo vería un token que otro ya rotó.
 */
public final class LivenessCoordinator {

  /** `CLOSED` obliga a terminar la sesión; `ALIVE` permite seguir. */
  public enum Verdict {
    ALIVE,
    CLOSED
  }

  private static final Logger LOG = LoggerFactory.getLogger(LivenessCoordinator.class);
  private static final String NO_STATE = "sin_estado";

  private final LivenessStore store;
  private final RefreshTokenClient client;
  private final LivenessProperties properties;
  private final Duration margin;
  private final Clock clock;
  private final Supplier<MeterRegistry> meters;

  /**
   * @param margin cuánto sobrevive la sesión sin una renovación correcta; nunca más que el límite
   *     absoluto (ADR-0020, regla 2)
   */
  public LivenessCoordinator(
      LivenessStore store,
      RefreshTokenClient client,
      LivenessProperties properties,
      Duration margin,
      Clock clock,
      Supplier<MeterRegistry> meters) {
    this.store = store;
    this.client = client;
    this.properties = properties;
    this.margin = margin;
    this.clock = clock;
    this.meters = meters;
  }

  public Verdict verify(String linkId) {
    Instant now = clock.instant();
    Optional<LivenessState> state = store.find(linkId);
    if (state.isEmpty()) {
      return closed(NO_STATE);
    }
    if (due(state.get(), now)) {
      Verdict renewal = renew(linkId, now);
      if (renewal == Verdict.CLOSED) {
        return Verdict.CLOSED;
      }
      state = store.find(linkId);
      if (state.isEmpty()) {
        return closed(NO_STATE);
      }
    }
    if (Duration.between(state.get().lastRenewed(), now).compareTo(margin) > 0) {
      store.delete(linkId);
      return closed("margen_agotado");
    }
    return Verdict.ALIVE;
  }

  private boolean due(LivenessState state, Instant now) {
    return !now.isBefore(state.lastRenewed().plus(properties.renewalInterval()))
        && !now.isBefore(state.retryNotBefore());
  }

  @SuppressWarnings("PMD.AvoidCatchingGenericException") // el cerrojo se libera pase lo que pase
  private Verdict renew(String linkId, Instant now) {
    Optional<String> lock = store.acquireLock(linkId, properties.lockTtl(), properties.lockWait());
    if (lock.isEmpty()) {
      // Otra petición está renovando; esperar más solo agotaría hilos (regla 12).
      count("lock_agotado");
      return Verdict.ALIVE;
    }
    try {
      Optional<LivenessState> fresh = store.find(linkId);
      if (fresh.isEmpty()) {
        return closed(NO_STATE);
      }
      // Regla 5/8: quien esperaba el cerrojo ve el estado ya actualizado y no renueva otra vez.
      if (!due(fresh.get(), now)) {
        return Verdict.ALIVE;
      }
      return renewWithClavaris(linkId, fresh.get(), now);
    } finally {
      store.release(linkId, lock.get());
    }
  }

  private Verdict renewWithClavaris(String linkId, LivenessState state, Instant now) {
    String refreshToken;
    try {
      refreshToken = store.refreshTokenOf(linkId, state);
    } catch (RefreshTokenCipher.UndecipherableTokenException _) {
      store.delete(linkId);
      return closed("token_ilegible");
    }
    RefreshTokenClient.Result result = client.refresh(refreshToken);
    switch (result) {
      case RefreshTokenClient.Result.Renewed(var newRefreshToken) -> {
        store.recordRenewed(linkId, newRefreshToken, state, now);
        count("renovado");
        return Verdict.ALIVE;
      }
      case RefreshTokenClient.Result.InvalidGrant() -> {
        store.delete(linkId);
        return closed("invalid_grant");
      }
      case RefreshTokenClient.Result.Transient(var reason) -> {
        LOG.warn("event=liveness_renewal_failed reason={}", reason);
        count("fallo_transitorio");
        if (!properties.failOpenEnabled()) {
          store.delete(linkId);
          return closed("cierre_forzado");
        }
        store.recordTransientFailure(linkId, state, now.plus(properties.negativeCache()));
        return Verdict.ALIVE;
      }
    }
  }

  private Verdict closed(String reason) {
    LOG.info("event=session_closed_by_liveness reason={}", reason);
    count("cerrada_" + reason);
    return Verdict.CLOSED;
  }

  private void count(String outcome) {
    MeterRegistry registry = meters.get();
    if (registry != null) {
      registry.counter("jobseeker_liveness_total", "outcome", outcome).increment();
    }
  }
}
