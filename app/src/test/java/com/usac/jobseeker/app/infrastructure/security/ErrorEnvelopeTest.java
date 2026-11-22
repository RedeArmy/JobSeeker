package com.usac.jobseeker.app.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.time.Instant;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * El sobre de error estándar (api-contract-overview.md §6): forma estable y nunca datos sensibles.
 */
class ErrorEnvelopeTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  private static MockHttpServletResponse write(
      MockHttpServletRequest request, int status, String code, String message) throws IOException {
    MockHttpServletResponse response = new MockHttpServletResponse();
    ErrorEnvelope.write(request, response, status, code, message);
    return response;
  }

  @Test
  void theBodyIsAnErrorObjectWithExactlyCodeMessageTraceIdAndTimestamp() throws Exception {
    MockHttpServletResponse response =
        write(new MockHttpServletRequest(), 403, "ACCESS_DENIED", "No tienes permiso.");

    JsonNode root = JSON.readTree(response.getContentAsString());

    assertThat(root.propertyNames()).containsExactly("error");
    assertThat(Set.copyOf(root.get("error").propertyNames()))
        .containsExactlyInAnyOrder("code", "message", "traceId", "timestamp");
    assertThat(root.get("error").get("code").asString()).isEqualTo("ACCESS_DENIED");
    assertThat(root.get("error").get("message").asString()).isEqualTo("No tienes permiso.");
  }

  @Test
  void theStatusAndTheJsonContentTypeAreSet() throws Exception {
    MockHttpServletResponse response = write(new MockHttpServletRequest(), 503, "X", "y");

    assertThat(response.getStatus()).isEqualTo(503);
    assertThat(response.getContentType()).startsWith("application/json");
    assertThat(response.getCharacterEncoding()).isEqualTo("UTF-8");
  }

  @Test
  void theTraceIdOfTheRequestIsCarriedIntoTheBody() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setAttribute(TraceIdFilter.ATTRIBUTE, "traza-123");

    JsonNode error = JSON.readTree(write(request, 401, "C", "m").getContentAsString()).get("error");

    assertThat(error.get("traceId").asString()).isEqualTo("traza-123");
  }

  @Test
  void theTimestampIsAnIsoInstantCloseToNow() throws Exception {
    Instant before = Instant.now();

    JsonNode error =
        JSON.readTree(write(new MockHttpServletRequest(), 401, "C", "m").getContentAsString())
            .get("error");

    Instant stamped = Instant.parse(error.get("timestamp").asString());
    assertThat(stamped).isBetween(before.minusSeconds(1), Instant.now().plusSeconds(1));
  }

  @Test
  void quotesNewlinesAndAccentsInTheMessageStillProduceValidJson() throws Exception {
    String awkward = "Dijo \"hola\"\nen dos líneas con acentos: á é ñ y una barra \\";

    JsonNode error =
        JSON.readTree(write(new MockHttpServletRequest(), 400, "C", awkward).getContentAsString())
            .get("error");

    assertThat(error.get("message").asString()).isEqualTo(awkward);
  }

  @Test
  void theResponseIsExplicitlyDeclaredAsUtf8SoAccentsSurviveInAnyServletContainer()
      throws Exception {
    // MockHttpServletResponse ya usa UTF-8 para JSON, así que se verifica la llamada al contrato de
    // la API de servlets: sin ella, un contenedor real escribiría en ISO-8859-1.
    MockHttpServletResponse response = org.mockito.Mockito.spy(new MockHttpServletResponse());

    ErrorEnvelope.write(new MockHttpServletRequest(), response, 400, "C", "Sesión inválida");

    org.mockito.Mockito.verify(response).setCharacterEncoding("UTF-8");
  }
}
