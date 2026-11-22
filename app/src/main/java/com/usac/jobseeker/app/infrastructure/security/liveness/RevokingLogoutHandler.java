package com.usac.jobseeker.app.infrastructure.security.liveness;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.logout.LogoutHandler;

/**
 * Al cerrar sesión revoca el refresh token en Clavaris (ADR-0020, regla 3): revocarlo termina allí
 * su sesión y evita que un token de 30 días quede vivo. Debe ejecutarse antes de que se invalide la
 * sesión local, porque de ella sale el identificador de enlace.
 *
 * <p>Es de mejor esfuerzo: si Clavaris no responde, el cierre local se completa igual y el token
 * queda cifrado en Redis solo hasta que venza el registro.
 */
public final class RevokingLogoutHandler implements LogoutHandler {

  private static final Logger LOG = LoggerFactory.getLogger(RevokingLogoutHandler.class);

  private final LivenessStore store;
  private final RefreshTokenClient client;

  public RevokingLogoutHandler(LivenessStore store, RefreshTokenClient client) {
    this.store = store;
    this.client = client;
  }

  @Override
  @SuppressWarnings("PMD.AvoidCatchingGenericException") // cerrar la sesión local no debe fallar
  public void logout(
      HttpServletRequest request, HttpServletResponse response, Authentication authentication) {
    HttpSession session = request.getSession(false);
    if (session == null
        || !(session.getAttribute(VaultingAuthorizedClientRepository.LINK_ATTRIBUTE)
            instanceof String linkId)) {
      return;
    }
    try {
      Optional<LivenessState> state = store.find(linkId);
      if (state.isPresent()) {
        boolean revoked = client.revoke(store.refreshTokenOf(linkId, state.get()));
        LOG.info("event=logout_refresh_token_revoked revoked={}", revoked);
      }
    } catch (RuntimeException failure) {
      LOG.warn("event=logout_revocation_skipped reason={}", failure.getClass().getSimpleName());
    } finally {
      store.delete(linkId);
    }
  }
}
