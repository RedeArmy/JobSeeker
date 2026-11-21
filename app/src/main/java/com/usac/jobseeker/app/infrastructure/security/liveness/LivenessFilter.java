package com.usac.jobseeker.app.infrastructure.security.liveness;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Cierra la sesión local cuando Clavaris ya no la respalda (ADR-0020, prueba de vida): cuenta
 * suspendida o borrada, token revocado o margen agotado. Sin esto, una sesión sobreviviría a la
 * baja de la cuenta hasta la inactividad o el límite absoluto.
 *
 * <p>Una sesión autenticada sin estado de vida se cierra también (fallo cerrado): solo existe si el
 * login no pasó por el almacén, y no hay forma de comprobar que siga respaldada.
 */
public final class LivenessFilter extends OncePerRequestFilter {

  private static final Logger LOG = LoggerFactory.getLogger(LivenessFilter.class);
  private static final String LOGOUT_PATH = "/app/logout";

  private final LivenessCoordinator coordinator;
  private final LivenessStore store;

  public LivenessFilter(LivenessCoordinator coordinator, LivenessStore store) {
    this.coordinator = coordinator;
    this.store = store;
  }

  // Los recursos estáticos y las sondas no actúan como la persona; salir siempre se permite.
  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    String path = request.getRequestURI();
    return LOGOUT_PATH.equals(path)
        || path.startsWith("/css/")
        || path.startsWith("/js/")
        || path.startsWith("/actuator/")
        || "/favicon.ico".equals(path);
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    HttpSession session = request.getSession(false);
    if (!(authentication instanceof OAuth2AuthenticationToken) || session == null) {
      chain.doFilter(request, response);
      return;
    }
    String linkId =
        (String) session.getAttribute(VaultingAuthorizedClientRepository.LINK_ATTRIBUTE);
    LivenessCoordinator.Verdict verdict;
    try {
      verdict = linkId == null ? LivenessCoordinator.Verdict.CLOSED : coordinator.verify(linkId);
    } catch (DataAccessException redisFailure) {
      // Redis es también el almacén de la sesión: si falla aquí, la sesión ya habría fallado. Se
      // deja seguir y se avisa; cerrar a todos por una caída de Redis no aporta seguridad.
      LOG.warn("event=liveness_check_skipped reason=redis_no_disponible");
      chain.doFilter(request, response);
      return;
    }
    if (verdict == LivenessCoordinator.Verdict.CLOSED) {
      if (linkId != null) {
        store.delete(linkId);
      }
      SecurityContextHolder.clearContext();
      session.invalidate();
    }
    chain.doFilter(request, response);
  }
}
