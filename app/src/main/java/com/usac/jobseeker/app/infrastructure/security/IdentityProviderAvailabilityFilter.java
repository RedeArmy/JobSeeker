package com.usac.jobseeker.app.infrastructure.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Si Clavaris no se puede alcanzar o no está configurado, el inicio de sesión responde 503 con
 * `Retry-After` en vez de un 500 genérico. No afecta a las sesiones ya abiertas: viven en Redis y
 * no dependen de Clavaris para servir páginas (ADR-0020, sección de degradación).
 */
final class IdentityProviderAvailabilityFilter extends OncePerRequestFilter {

  private static final String AUTHORIZATION_PATH =
      "/oauth2/authorization/" + LazyClavarisClientRegistrationRepository.REGISTRATION_ID;
  private static final String CALLBACK_PATH =
      "/login/oauth2/code/" + LazyClavarisClientRegistrationRepository.REGISTRATION_ID;

  private final ClientRegistrationRepository clients;
  private final Duration retryAfter;

  IdentityProviderAvailabilityFilter(ClientRegistrationRepository clients, Duration retryAfter) {
    this.clients = clients;
    this.retryAfter = retryAfter;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String path = request.getRequestURI();
    boolean loginStep = AUTHORIZATION_PATH.equals(path) || CALLBACK_PATH.equals(path);
    if (loginStep
        && clients.findByRegistrationId(LazyClavarisClientRegistrationRepository.REGISTRATION_ID)
            == null) {
      unavailable(request, response);
      return;
    }
    chain.doFilter(request, response);
  }

  private void unavailable(HttpServletRequest request, HttpServletResponse response)
      throws IOException {
    response.setHeader(HttpHeaders.RETRY_AFTER, String.valueOf(retryAfter.toSeconds()));
    String accept = request.getHeader(HttpHeaders.ACCEPT);
    if (accept != null && accept.contains(MediaType.TEXT_HTML_VALUE)) {
      // Página sin scripts ni estilos en línea, compatible con la CSP estricta.
      response.setStatus(503);
      response.setContentType("text/html");
      response.setCharacterEncoding(StandardCharsets.UTF_8.name());
      response
          .getWriter()
          .write(
              "<!DOCTYPE html><html lang=\"es\"><head><meta charset=\"UTF-8\"/><title>JobSeeker</title></head>"
                  + "<body><h1>No podemos iniciar sesión ahora</h1>"
                  + "<p>El servicio de identidad no responde. Inténtalo de nuevo en unos segundos.</p>"
                  + "</body></html>");
    } else {
      ErrorEnvelope.write(
          request,
          response,
          503,
          "IDENTITY_PROVIDER_UNAVAILABLE",
          "El servicio de identidad no responde; inténtalo de nuevo en unos segundos.");
    }
  }
}
