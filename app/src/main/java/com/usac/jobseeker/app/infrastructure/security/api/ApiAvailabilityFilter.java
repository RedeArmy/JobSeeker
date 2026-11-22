package com.usac.jobseeker.app.infrastructure.security.api;

import com.usac.jobseeker.app.infrastructure.security.ErrorEnvelope;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Si no se puede comprobar el token porque Clavaris no responde, la API contesta 503 con
 * `Retry-After` y no un 401 que haría creer al cliente que su token es malo, ni un 500.
 *
 * <p>Spring relanza como {@link AuthenticationServiceException} los fallos que no son del token
 * (JWKS inalcanzable, Clavaris sin descubrir); este filtro, colocado antes del filtro Bearer, los
 * recoge.
 */
public final class ApiAvailabilityFilter extends OncePerRequestFilter {

  private static final Logger LOG = LoggerFactory.getLogger(ApiAvailabilityFilter.class);

  private final Duration retryAfter;

  public ApiAvailabilityFilter(Duration retryAfter) {
    this.retryAfter = retryAfter;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    try {
      chain.doFilter(request, response);
    } catch (AuthenticationServiceException unavailable) {
      LOG.warn(
          "event=api_identity_provider_unavailable cause={}",
          unavailable.getCause() == null
              ? unavailable.getClass().getSimpleName()
              : unavailable.getCause().getClass().getSimpleName());
      response.setHeader(HttpHeaders.RETRY_AFTER, String.valueOf(retryAfter.toSeconds()));
      ErrorEnvelope.write(
          request,
          response,
          503,
          "IDENTITY_PROVIDER_UNAVAILABLE",
          "El servicio de identidad no responde; inténtalo de nuevo en unos segundos.");
    }
  }
}
