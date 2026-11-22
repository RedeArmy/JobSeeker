package com.usac.jobseeker.app.infrastructure.security.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;

/** CORS de la API (ADR-0020): cerrado por defecto y, si se abre, solo a orígenes explícitos. */
class ApiConfigurationTest {

  private final ApiConfiguration configuration = new ApiConfiguration();

  private CorsConfiguration corsFor(List<String> origins, String path) {
    CorsConfigurationSource source =
        configuration.apiCorsConfigurationSource(
            new ApiAuthProperties(List.of(), origins, Duration.ofSeconds(30)));
    return source.getCorsConfiguration(new MockHttpServletRequest("GET", path));
  }

  @Test
  void withNoConfiguredOriginsNoOriginIsAllowed() {
    CorsConfiguration cors = corsFor(List.of(), "/api/v1/cualquiera");

    assertThat(cors.checkOrigin("https://evil.example")).isNull();
    assertThat(cors.checkOrigin("http://localhost:3000")).isNull();
    assertThat(cors.getAllowedOrigins()).isEmpty();
  }

  @Test
  void aConfiguredOriginIsAllowedAndOthersStillAreNot() {
    CorsConfiguration cors = corsFor(List.of("https://app.ejemplo.test"), "/api/v1/x");

    assertThat(cors.checkOrigin("https://app.ejemplo.test")).isEqualTo("https://app.ejemplo.test");
    assertThat(cors.checkOrigin("https://app.ejemplo.test.evil.example")).isNull();
    assertThat(cors.checkOrigin("http://app.ejemplo.test")).as("otro esquema").isNull();
  }

  @Test
  void credentialsAreNeverAllowedBecauseBearerTokensAreNotAmbient() {
    assertThat(corsFor(List.of("https://app.ejemplo.test"), "/api/v1/x").getAllowCredentials())
        .isFalse();
  }

  @Test
  void onlyTheMethodsAndHeadersTheApiNeedsAreAllowed() {
    CorsConfiguration cors = corsFor(List.of("https://app.ejemplo.test"), "/api/v1/x");

    assertThat(cors.checkHttpMethod(org.springframework.http.HttpMethod.GET)).isNotNull();
    assertThat(cors.checkHttpMethod(org.springframework.http.HttpMethod.DELETE)).isNotNull();
    assertThat(cors.checkHttpMethod(org.springframework.http.HttpMethod.TRACE)).isNull();
    assertThat(cors.checkHeaders(List.of("Authorization", "Content-Type"))).isNotNull();
    assertThat(cors.checkHeaders(List.of("X-Cabecera-Rara"))).isNull();
  }

  @Test
  void theConfigurationAppliesOnlyUnderTheApiPath() {
    assertThat(corsFor(List.of("https://app.ejemplo.test"), "/app/home")).isNull();
    assertThat(corsFor(List.of("https://app.ejemplo.test"), "/api/v1/algo/mas")).isNotNull();
  }

  @Test
  void thePreflightIsCachedForTenMinutesAndTheTraceIdIsExposed() {
    CorsConfiguration cors = corsFor(List.of("https://app.ejemplo.test"), "/api/v1/x");

    assertThat(cors.getMaxAge()).isEqualTo(600L);
    assertThat(cors.getExposedHeaders()).contains("X-Trace-Id", "Retry-After");
  }
}
