package com.usac.jobseeker.app.infrastructure.security.webhook;

import static org.assertj.core.api.Assertions.assertThat;

import com.usac.jobseeker.auth.application.usecase.handleclavariswebhook.HandleClavarisWebhookCommand;
import com.usac.jobseeker.auth.application.usecase.handleclavariswebhook.HandleClavarisWebhookResult;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * El filtro del receptor sin levantar Spring: los bordes del límite de cuerpo (incluido un cuerpo
 * sin `Content-Length`) y las métricas por resultado, que la integración no mira.
 */
class ClavarisWebhookFilterTest {

  private static final Instant NOW = Instant.parse("2026-11-22T12:00:00Z");
  private static final String SECRET = "whsec-unidad";
  private static final int MAX = 300;

  private final List<HandleClavarisWebhookCommand> received = new ArrayList<>();
  private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
  private HandleClavarisWebhookResult result = HandleClavarisWebhookResult.APPLIED;
  private boolean useCaseFails;

  private ClavarisWebhookFilter filter(String secret) {
    WebhookProperties properties = new WebhookProperties(secret, "", Duration.ofMinutes(5), MAX);
    return new ClavarisWebhookFilter(
        command -> {
          if (useCaseFails) {
            throw new IllegalStateException("base caída");
          }
          received.add(command);
          return result;
        },
        properties,
        meters,
        Clock.fixed(NOW, ZoneOffset.UTC));
  }

  /** Un cuerpo JSON válido rellenado hasta tener exactamente `size` bytes. */
  private static byte[] bodyOfSize(int size) {
    String head =
        "{\"accountId\":\""
            + UUID.randomUUID()
            + "\",\"occurredAt\":\"2026-11-22T12:00:00Z\",\"p\":\"";
    String tail = "\"}";
    int padding = size - head.length() - tail.length();
    return (head + "x".repeat(padding) + tail).getBytes(StandardCharsets.UTF_8);
  }

  private MockHttpServletRequest request(byte[] body, boolean declareLength) {
    // Sin longitud declarada (como un cuerpo `chunked`): el filtro debe acotar la lectura él mismo.
    MockHttpServletRequest request =
        new MockHttpServletRequest("POST", "/api/v1/webhooks/clavaris") {
          @Override
          public long getContentLengthLong() {
            return declareLength ? super.getContentLengthLong() : -1;
          }
        };
    request.setContent(body);
    request.addHeader("Clavaris-Signature", WebhookSigner.header(NOW, body, List.of(SECRET)));
    request.addHeader("Clavaris-Event-Type", "account.suspended");
    request.addHeader("Clavaris-Event-Id", UUID.randomUUID().toString());
    return request;
  }

  private MockHttpServletResponse run(MockHttpServletRequest request)
      throws IOException, jakarta.servlet.ServletException {
    MockHttpServletResponse response = new MockHttpServletResponse();
    filter(SECRET).doFilter(request, response, new MockFilterChain());
    return response;
  }

  private double count(String outcome) {
    var counter = meters.find(ClavarisWebhookFilter.METRIC).tag("outcome", outcome).counter();
    return counter == null ? 0 : counter.count();
  }

  // ---- el límite del cuerpo, en el borde ----

  @Test
  void aBodyOfExactlyTheLimitIsAccepted() throws Exception {
    assertThat(run(request(bodyOfSize(MAX), true)).getStatus()).isEqualTo(204);
  }

  @Test
  void aBodyOneByteOverTheLimitIsRejectedByItsDeclaredLength() throws Exception {
    assertThat(run(request(bodyOfSize(MAX + 1), true)).getStatus()).isEqualTo(413);
    assertThat(received).isEmpty();
  }

  @Test
  void withoutADeclaredLengthABodyOfExactlyTheLimitIsStillAccepted() throws Exception {
    assertThat(run(request(bodyOfSize(MAX), false)).getStatus()).isEqualTo(204);
  }

  @Test
  void withoutADeclaredLengthABodyOverTheLimitIsRejectedWithoutBeingReadWhole() throws Exception {
    MockHttpServletResponse response = run(request(bodyOfSize(MAX + 1), false));

    assertThat(response.getStatus()).isEqualTo(413);
    assertThat(response.getContentAsString()).contains("PAYLOAD_TOO_LARGE");
    assertThat(received).isEmpty();
    assertThat(count("too_large")).isOne();
  }

  // ---- métricas por resultado ----

  @Test
  void eachResultOfTheUseCaseIsCountedUnderItsOwnOutcome() throws Exception {
    for (HandleClavarisWebhookResult each : HandleClavarisWebhookResult.values()) {
      result = each;
      run(request(bodyOfSize(200), true));
    }

    for (HandleClavarisWebhookResult each : HandleClavarisWebhookResult.values()) {
      assertThat(count(each.name().toLowerCase(java.util.Locale.ROOT))).as(each.name()).isOne();
    }
  }

  @Test
  void rejectionsAreCountedByReason() throws Exception {
    MockHttpServletRequest forged = request(bodyOfSize(200), true);
    forged.removeHeader("Clavaris-Signature");
    forged.addHeader("Clavaris-Signature", "t=1,v1=" + "0".repeat(64));
    run(forged);
    MockHttpServletRequest wrongMethod =
        new MockHttpServletRequest("GET", "/api/v1/webhooks/clavaris");
    filter(SECRET).doFilter(wrongMethod, new MockHttpServletResponse(), new MockFilterChain());
    MockHttpServletResponse closed = new MockHttpServletResponse();
    filter("").doFilter(request(bodyOfSize(200), true), closed, new MockFilterChain());
    MockHttpServletRequest unreadable = request("{}".getBytes(StandardCharsets.UTF_8), true);
    run(unreadable);
    useCaseFails = true;
    run(request(bodyOfSize(200), true));

    assertThat(count("signature_mismatch")).isOne();
    assertThat(count("method")).isOne();
    assertThat(count("closed")).isOne();
    assertThat(count("invalid")).isOne();
    assertThat(count("error")).isOne();
    assertThat(closed.getStatus()).isEqualTo(503);
  }

  @Test
  void aFailureOfTheUseCaseAnswers503SoClavarisRetries() throws Exception {
    useCaseFails = true;

    MockHttpServletResponse response = run(request(bodyOfSize(200), true));

    assertThat(response.getStatus()).isEqualTo(503);
    assertThat(response.getContentAsString()).contains("WEBHOOK_UNAVAILABLE");
  }

  @Test
  void theCommandCarriesTheEventIdAndTypeOfTheHeaders() throws Exception {
    MockHttpServletRequest request = request(bodyOfSize(200), true);
    String eventId = request.getHeader("Clavaris-Event-Id");
    request.addHeader("Clavaris-Trace-Id", "traza-1");

    run(request);

    assertThat(received)
        .singleElement()
        .satisfies(
            c -> {
              assertThat(c.eventId()).hasToString(eventId);
              assertThat(c.eventType()).isEqualTo("account.suspended");
              assertThat(c.traceId()).isEqualTo("traza-1");
            });
  }

  @Test
  void aBodyDeclaredOverTheLimitIsRejectedWithoutReadingASingleByte() throws Exception {
    // La comprobación por `Content-Length` existe para no leer un cuerpo que ya se sabe demasiado
    // grande.
    MockHttpServletRequest unreadable =
        new MockHttpServletRequest("POST", "/api/v1/webhooks/clavaris") {
          @Override
          public long getContentLengthLong() {
            return MAX + 1L;
          }

          @Override
          public jakarta.servlet.ServletInputStream getInputStream() {
            throw new AssertionError("no debía leerse el cuerpo");
          }
        };
    MockHttpServletResponse response = new MockHttpServletResponse();

    filter(SECRET).doFilter(unreadable, response, new MockFilterChain());

    assertThat(response.getStatus()).isEqualTo(413);
  }
}
