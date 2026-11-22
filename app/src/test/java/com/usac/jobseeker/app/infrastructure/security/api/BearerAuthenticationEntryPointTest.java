package com.usac.jobseeker.app.infrastructure.security.api;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * La respuesta 401 de la API: cabecera `WWW-Authenticate` (RFC 6750) y sobre estándar, sin decirle
 * a quien prueba tokens por qué falló el suyo.
 */
class BearerAuthenticationEntryPointTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  private final BearerAuthenticationEntryPoint entryPoint = new BearerAuthenticationEntryPoint();
  private final MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/x");
  private final MockHttpServletResponse response = new MockHttpServletResponse();

  private JsonNode errorBody() throws Exception {
    return JSON.readTree(response.getContentAsString()).get("error");
  }

  @Test
  void withoutAnyTokenItAsksForOneWithoutNamingAnError() throws Exception {
    entryPoint.commence(request, response, new InsufficientAuthenticationException("falta"));

    assertThat(response.getStatus()).isEqualTo(401);
    assertThat(response.getHeader("WWW-Authenticate")).isEqualTo("Bearer");
    assertThat(errorBody().get("code").asString()).isEqualTo("AUTHENTICATION_REQUIRED");
  }

  @Test
  void aRejectedTokenAnswersWithTheInvalidTokenErrorInTheHeaderAndItsOwnCodeInTheBody()
      throws Exception {
    entryPoint.commence(
        request,
        response,
        new InvalidBearerTokenException("The token was not issued for this application"));

    assertThat(response.getStatus()).isEqualTo(401);
    assertThat(response.getHeader("WWW-Authenticate")).isEqualTo("Bearer error=\"invalid_token\"");
    assertThat(errorBody().get("code").asString()).isEqualTo("TOKEN_INVALID");
  }

  @Test
  void theBodyNeverSaysWhyTheTokenWasRejected() throws Exception {
    entryPoint.commence(
        request,
        response,
        new InvalidBearerTokenException(
            "Jwt expired at 2026-11-22T12:00:00Z for audience secreto-interno"));

    assertThat(response.getContentAsString())
        .doesNotContain("expired")
        .doesNotContain("audience")
        .doesNotContain("secreto-interno");
  }

  @Test
  void aMalformedRequestIsA400AndNotA401AsRfc6750Says() throws Exception {
    entryPoint.commence(
        request,
        response,
        new OAuth2AuthenticationException(
            new OAuth2Error("invalid_request", "mala petición", null)));

    assertThat(response.getStatus()).isEqualTo(400);
    assertThat(response.getHeader("WWW-Authenticate"))
        .isEqualTo("Bearer error=\"invalid_request\"");
    assertThat(errorBody().get("code").asString()).isEqualTo("TOKEN_REQUEST_INVALID");
  }

  @Test
  void anyOtherOAuthErrorIsATokenRejectionWith401() throws Exception {
    entryPoint.commence(
        request,
        response,
        new OAuth2AuthenticationException(new OAuth2Error("otro_error", "x", null)));

    assertThat(response.getStatus()).isEqualTo(401);
    assertThat(errorBody().get("code").asString()).isEqualTo("TOKEN_INVALID");
  }

  @Test
  void theHeaderNeverCarriesTheDescriptionNorAnythingDerivedFromTheRequestHost() throws Exception {
    request.addHeader("Host", "atacante.example");
    entryPoint.commence(
        request, response, new InvalidBearerTokenException("Jwt expired at 2026-11-22T12:00:00Z"));

    assertThat(response.getHeader("WWW-Authenticate"))
        .isEqualTo("Bearer error=\"invalid_token\"")
        .doesNotContain("expired")
        .doesNotContain("atacante")
        .doesNotContain("resource_metadata");
  }

  @Test
  void anErrorCodeWithCharactersThatAreLegalForSpringButNotForTheHeaderIsCleanedUp()
      throws Exception {
    // OAuth2Error admite espacios, `;` y `,`; en `error="..."` romperían el formato de la cabecera.
    entryPoint.commence(
        request,
        response,
        new OAuth2AuthenticationException(new OAuth2Error("invalid token; x=y, z", null, null)));

    assertThat(response.getHeader("WWW-Authenticate"))
        .isEqualTo("Bearer error=\"invalidtokenxyz\"");
  }

  @Test
  void theResponseIsJsonWithTheStandardEnvelopeFields() throws Exception {
    entryPoint.commence(request, response, new InsufficientAuthenticationException("falta"));

    assertThat(response.getContentType()).startsWith("application/json");
    assertThat(errorBody().propertyNames())
        .containsExactlyInAnyOrder("code", "message", "traceId", "timestamp");
  }

  // ---- el registro de eventos: el motivo queda en el servidor, nunca el token ----

  private ListAppender<ILoggingEvent> captureLog() {
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    ((Logger) LoggerFactory.getLogger(BearerAuthenticationEntryPoint.class)).addAppender(appender);
    return appender;
  }

  private static List<String> messages(ListAppender<ILoggingEvent> appender) {
    return appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
  }

  @Test
  void aRejectionIsLoggedWithItsReasonAndNeverWithTheTokenItself() throws Exception {
    ListAppender<ILoggingEvent> log = captureLog();
    request.addHeader("Authorization", "Bearer eyJsecreto.cuerpo-secreto.firma-secreta");

    entryPoint.commence(
        request, response, new InvalidBearerTokenException("Jwt expired at 2026-11-22T12:00:00Z"));

    assertThat(messages(log))
        .singleElement()
        .isEqualTo("event=api_token_rejected reason=Jwt expired at 2026-11-22T12:00:00Z")
        .satisfies(
            line ->
                assertThat(line)
                    .doesNotContain("secreto")
                    .doesNotContain("Bearer")
                    .doesNotContain("eyJ"));
  }

  @Test
  void withoutADescriptionTheLogFallsBackToTheErrorCode() throws Exception {
    ListAppender<ILoggingEvent> log = captureLog();

    entryPoint.commence(
        request,
        response,
        new OAuth2AuthenticationException(new OAuth2Error("invalid_token", null, null)));

    assertThat(messages(log)).containsExactly("event=api_token_rejected reason=invalid_token");
  }

  @Test
  void aDescriptionCannotForgeAnotherLogLineBecauseNewlinesAreReplaced() throws Exception {
    ListAppender<ILoggingEvent> log = captureLog();

    entryPoint.commence(
        request,
        response,
        new OAuth2AuthenticationException(
            new OAuth2Error("invalid_token", "uno\nevent=login_succeeded accountId=x", null)));

    assertThat(messages(log))
        .singleElement()
        .satisfies(line -> assertThat(line).doesNotContain("\n"))
        .isEqualTo("event=api_token_rejected reason=uno event=login_succeeded accountId=x");
  }

  @Test
  void aRequestWithoutATokenIsNotLoggedAsARejectedToken() throws Exception {
    ListAppender<ILoggingEvent> log = captureLog();

    entryPoint.commence(request, response, new InsufficientAuthenticationException("falta"));

    assertThat(messages(log)).isEmpty();
  }

  @Test
  void theMessageOfTheBodyIsFixedTextForEachKindOfResponseAndNeverTheErrorDescription()
      throws Exception {
    entryPoint.commence(
        request, response, new InvalidBearerTokenException("detalle interno del validador"));
    assertThat(errorBody().get("message").asString()).isEqualTo("El token no es válido.");

    MockHttpServletResponse malformed = new MockHttpServletResponse();
    entryPoint.commence(
        request,
        malformed,
        new OAuth2AuthenticationException(new OAuth2Error("invalid_request", "detalle", null)));
    assertThat(JSON.readTree(malformed.getContentAsString()).get("error").get("message").asString())
        .isEqualTo("La petición no está bien formada.");

    MockHttpServletResponse missing = new MockHttpServletResponse();
    entryPoint.commence(request, missing, new InsufficientAuthenticationException("falta"));
    assertThat(JSON.readTree(missing.getContentAsString()).get("error").get("message").asString())
        .isEqualTo("Se requiere autenticación.");
  }
}
