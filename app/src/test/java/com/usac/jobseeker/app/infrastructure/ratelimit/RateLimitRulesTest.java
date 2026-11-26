package com.usac.jobseeker.app.infrastructure.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;

/** Qué política recibe cada petición: lo que hace transversal al límite de tasa. */
class RateLimitRulesTest {

  private static Optional<String> policy(String method, String uri) {
    return RateLimitRules.policyFor(new MockHttpServletRequest(method, uri));
  }

  @ParameterizedTest
  @CsvSource({
    "GET, /oauth2/authorization/clavaris, login",
    "GET, /login/oauth2/code/clavaris, login",
    "POST, /api/v1/webhooks/clavaris, webhook",
    "GET, /api/v1/me, read",
    "HEAD, /api/v1/me, read",
    "OPTIONS, /api/v1/me, read",
    "GET, /app/home, read",
    "POST, /api/v1/anything, write",
    "PUT, /api/v1/anything, write",
    "PATCH, /api/v1/anything, write",
    "DELETE, /api/v1/anything, write",
    "POST, /app/logout, write",
    "GET, /api/v1/matching/jobs/7d1c1b5e-0000-4000-8000-000000000001/suggestions, suggestions"
  })
  void everyRouteGetsThePolicyOfItsKind(String method, String uri, String expected) {
    assertThat(policy(method, uri)).contains(expected);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "/actuator/health/liveness",
        "/actuator/prometheus",
        "/css/site.css",
        "/js/htmx.min.js",
        "/favicon.ico",
        "/error"
      })
  void operationalAndStaticRoutesAreNotLimited(String uri) {
    assertThat(policy("GET", uri)).isEmpty();
  }

  // ADR-0021 §4: toda subida tiene su cubo, reconocida por el tipo de contenido y no por la ruta.
  @Test
  void anyMultipartRequestUsesTheUploadPolicyWhateverItsRoute() {
    var cv = new MockHttpServletRequest("POST", "/api/v1/me/candidate-profile/cv");
    cv.setContentType("multipart/form-data; boundary=x");
    var future = new MockHttpServletRequest("PUT", "/app/cualquier/subida");
    future.setContentType("MULTIPART/form-data");
    var json = new MockHttpServletRequest("POST", "/api/v1/me/candidate-profile/cv");
    json.setContentType("application/json");

    assertThat(RateLimitRules.policyFor(cv)).contains("upload");
    assertThat(RateLimitRules.policyFor(future)).contains("upload");
    assertThat(RateLimitRules.policyFor(json)).contains("write");
  }

  @Test
  void aRouteNobodyDeclaredIsLimitedAsReadOrWriteInsteadOfEscaping() {
    assertThat(policy("GET", "/ruta/que/nadie/declaro")).contains("read");
    assertThat(policy("POST", "/ruta/que/nadie/declaro")).contains("write");
  }

  @Test
  void theWebhookPolicyIsExactlyForItsOwnPathAndNotForItsPrefix() {
    assertThat(policy("POST", "/api/v1/webhooks/clavaris/otra")).contains("write");
    assertThat(policy("POST", "/api/v1/webhooks/otro")).contains("write");
  }

  @Test
  void theContextPathIsIgnoredWhenChoosingThePolicy() {
    MockHttpServletRequest request =
        new MockHttpServletRequest("POST", "/jobseeker/api/v1/webhooks/clavaris");
    request.setContextPath("/jobseeker");

    assertThat(RateLimitRules.policyFor(request)).contains("webhook");
  }
}
