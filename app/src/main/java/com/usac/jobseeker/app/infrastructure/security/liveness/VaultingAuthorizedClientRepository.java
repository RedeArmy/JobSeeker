package com.usac.jobseeker.app.infrastructure.security.liveness;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizedClientRepository;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;

/**
 * Sustituye al repositorio de clientes autorizados por defecto de Spring, que guardaría el refresh
 * token **en claro dentro de la sesión** (ADR-0020, regla 3). Aquí el token va cifrado a Redis y la
 * sesión solo guarda un identificador de enlace. JobSeeker no llama a ninguna API de Clavaris con
 * el access token, así que no se conserva y {@code load} no devuelve nada.
 */
public final class VaultingAuthorizedClientRepository implements OAuth2AuthorizedClientRepository {

  /**
   * Atributo de sesión con el identificador de enlace; sobrevive a la rotación del ID (regla 6).
   */
  public static final String LINK_ATTRIBUTE = "JOBSEEKER_LIVENESS_LINK";

  private static final Logger LOG =
      LoggerFactory.getLogger(VaultingAuthorizedClientRepository.class);

  private final LivenessStore store;
  private final RefreshTokenCipher cipher;
  private final Clock clock;

  public VaultingAuthorizedClientRepository(
      LivenessStore store, RefreshTokenCipher cipher, Clock clock) {
    this.store = store;
    this.cipher = cipher;
    this.clock = clock;
  }

  @Override
  public void saveAuthorizedClient(
      OAuth2AuthorizedClient client,
      Authentication principal,
      HttpServletRequest request,
      HttpServletResponse response) {
    // Sin la prueba de vida no hay forma de saber si la cuenta sigue activa: el login se rechaza
    // (fallo cerrado, ADR-0020) en vez de abrir una sesión que nadie puede revocar a tiempo.
    if (client.getRefreshToken() == null) {
      LOG.warn("event=login_rejected reason=clavaris_no_emitio_refresh_token");
      throw new OAuth2AuthenticationException(new OAuth2Error("refresh_token_missing"));
    }
    if (!cipher.available()) {
      LOG.error("event=login_rejected reason=sin_clave_de_cifrado");
      throw new OAuth2AuthenticationException(new OAuth2Error("encryption_not_configured"));
    }
    HttpSession session = request.getSession(true);
    String previous = (String) session.getAttribute(LINK_ATTRIBUTE);
    if (previous != null) {
      store.delete(previous); // un login sobre una sesión que ya tenía enlace no lo deja huérfano
    }
    session.setAttribute(
        LINK_ATTRIBUTE, store.begin(client.getRefreshToken().getTokenValue(), clock.instant()));
  }

  @Override
  @SuppressWarnings("unchecked")
  public <T extends OAuth2AuthorizedClient> T loadAuthorizedClient(
      String clientRegistrationId, Authentication principal, HttpServletRequest request) {
    return null;
  }

  @Override
  public void removeAuthorizedClient(
      String clientRegistrationId,
      Authentication principal,
      HttpServletRequest request,
      HttpServletResponse response) {
    HttpSession session = request.getSession(false);
    if (session != null && session.getAttribute(LINK_ATTRIBUTE) instanceof String linkId) {
      store.delete(linkId);
      session.removeAttribute(LINK_ATTRIBUTE);
    }
  }
}
