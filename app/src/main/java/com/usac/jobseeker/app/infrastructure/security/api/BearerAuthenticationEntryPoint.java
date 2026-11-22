package com.usac.jobseeker.app.infrastructure.security.api;

import com.usac.jobseeker.app.infrastructure.security.ErrorEnvelope;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;

/**
 * Respuesta 401 de la API: la cabecera `WWW-Authenticate` que exige RFC 6750 (con el error cuando
 * se presentó un token) y el sobre de error estándar (api-contract-overview.md §6).
 *
 * <p>El cuerpo nunca dice por qué se rechazó el token: eso ayudaría a quien lo prueba. El motivo
 * queda en el log del servidor, sin el token.
 */
public final class BearerAuthenticationEntryPoint implements AuthenticationEntryPoint {

  private static final Logger LOG = LoggerFactory.getLogger(BearerAuthenticationEntryPoint.class);

  @Override
  public void commence(
      HttpServletRequest request, HttpServletResponse response, AuthenticationException exception)
      throws IOException {
    if (exception instanceof OAuth2AuthenticationException rejected) {
      String error = rejected.getError().getErrorCode();
      LOG.info("event=api_token_rejected reason={}", reasonOf(rejected));
      // RFC 6750: `invalid_request` (petición mal formada) es un 400; el resto de los rechazos,
      // 401.
      boolean malformed = "invalid_request".equals(error);
      // La cabecera lleva el código de error y no su descripción: decir por qué falló el token
      // ayudaría a quien los prueba, y Spring Security 7 además añade `resource_metadata=` con el
      // `Host` de la petición hacia un documento que no se sirve. Se escribe aquí a mano.
      response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer error=\"" + sanitize(error) + "\"");
      ErrorEnvelope.write(
          request,
          response,
          malformed ? 400 : 401,
          malformed ? "TOKEN_REQUEST_INVALID" : "TOKEN_INVALID",
          malformed ? "La petición no está bien formada." : "El token no es válido.");
    } else {
      response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
      ErrorEnvelope.write(
          request, response, 401, "AUTHENTICATION_REQUIRED", "Se requiere autenticación.");
    }
  }

  // La descripción es texto nuestro o de Spring («Jwt expired at …»): no contiene el token.
  private static String reasonOf(OAuth2AuthenticationException exception) {
    String description = exception.getError().getDescription();
    return description == null
        ? exception.getError().getErrorCode()
        : description.replace('\n', ' ');
  }

  // El código de error va entre comillas en una cabecera: nada que pueda romperlas.
  private static String sanitize(String errorCode) {
    return errorCode.replaceAll("[^A-Za-z0-9_.-]", "");
  }
}
