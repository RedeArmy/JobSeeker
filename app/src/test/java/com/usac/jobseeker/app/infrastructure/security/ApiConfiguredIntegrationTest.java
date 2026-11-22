package com.usac.jobseeker.app.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.usac.jobseeker.app.InfrastructureTestConfiguration;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * La API con la configuración que el MVP deja vacía: un origen CORS permitido y audiencias propias
 * (un cliente móvil). Comprueba que abrir la API es explícito y que no se abre nada más.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({InfrastructureTestConfiguration.class, ApiConfiguredIntegrationTest.TestEndpoints.class})
class ApiConfiguredIntegrationTest {

  private static final String ALLOWED_ORIGIN = "https://app.ejemplo.test";
  private static final StubIssuer ISSUER = startIssuer();
  private static final TestTokens KEYS = TestTokens.withKey("kid-configurada");

  private static StubIssuer startIssuer() {
    try {
      return new StubIssuer();
    } catch (java.io.IOException failure) {
      throw new IllegalStateException(failure);
    }
  }

  @DynamicPropertySource
  static void configure(DynamicPropertyRegistry registry) {
    registry.add("jobseeker.auth.clavaris.issuer-uri", ISSUER::issuerUri);
    registry.add("jobseeker.auth.clavaris.client-id", () -> TestTokens.CLIENT_ID);
    registry.add("jobseeker.auth.clavaris.client-secret", () -> "secreto-de-prueba");
    registry.add("jobseeker.auth.api.allowed-origins", () -> ALLOWED_ORIGIN);
    registry.add("jobseeker.auth.api.allowed-audiences", () -> "app-movil, app-web");
  }

  @AfterAll
  static void stop() {
    ISSUER.close();
  }

  @Autowired private MockMvc mvc;

  @BeforeEach
  void publishTheKeys() {
    ISSUER.jwks = KEYS.jwks();
  }

  private MockHttpServletRequestBuilder whoAmI(String audience) {
    String token =
        KEYS.token(ISSUER.issuerUri(), Instant.now())
            .subject(UUID.randomUUID().toString())
            .audience(audience)
            .build();
    return get("/api/v1/test/ping").header("Authorization", "Bearer " + token);
  }

  // ---- audiencias propias ----

  @Test
  void everyConfiguredAudienceIsAccepted() throws Exception {
    mvc.perform(whoAmI("app-movil")).andExpect(status().isOk());
    mvc.perform(whoAmI("app-web")).andExpect(status().isOk());
  }

  @Test
  void theClientIdOfJobSeekerIsNoLongerAcceptedOnceAudiencesAreConfiguredExplicitly()
      throws Exception {
    mvc.perform(whoAmI(TestTokens.CLIENT_ID)).andExpect(status().isUnauthorized());
  }

  @Test
  void anAudienceThatWasNotConfiguredIsStillRejected() throws Exception {
    mvc.perform(whoAmI("app-escritorio")).andExpect(status().isUnauthorized());
  }

  // ---- CORS abierto solo al origen configurado ----

  @Test
  void theConfiguredOriginPassesThePreflightWithExactlyWhatItAsksFor() throws Exception {
    MvcResult result =
        mvc.perform(
                options("/api/v1/test/ping")
                    .header("Origin", ALLOWED_ORIGIN)
                    .header("Access-Control-Request-Method", "GET")
                    .header("Access-Control-Request-Headers", "authorization"))
            .andExpect(status().isOk())
            .andReturn();

    assertThat(result.getResponse().getHeader("Access-Control-Allow-Origin"))
        .isEqualTo(ALLOWED_ORIGIN);
    assertThat(result.getResponse().getHeader("Access-Control-Allow-Credentials")).isNull();
    assertThat(result.getResponse().getHeader("Access-Control-Max-Age")).isEqualTo("600");
  }

  @Test
  void aSimpleRequestFromTheConfiguredOriginGetsItsCorsHeaders() throws Exception {
    MvcResult result =
        mvc.perform(whoAmI("app-movil").header("Origin", ALLOWED_ORIGIN))
            .andExpect(status().isOk())
            .andReturn();

    assertThat(result.getResponse().getHeader("Access-Control-Allow-Origin"))
        .isEqualTo(ALLOWED_ORIGIN);
    assertThat(result.getResponse().getHeader("Access-Control-Expose-Headers"))
        .contains("X-Trace-Id");
  }

  @Test
  void anyOtherOriginIsStillRejectedEvenWithAValidToken() throws Exception {
    for (String origin :
        new String[] {
          "https://evil.example",
          "http://app.ejemplo.test",
          "https://app.ejemplo.test.evil.example",
          "https://sub.app.ejemplo.test"
        }) {
      mvc.perform(whoAmI("app-movil").header("Origin", origin)).andExpect(status().isForbidden());
    }
  }

  @Test
  void aPreflightAskingForAMethodOrHeaderThatIsNotAllowedIsRejected() throws Exception {
    mvc.perform(
            options("/api/v1/test/ping")
                .header("Origin", ALLOWED_ORIGIN)
                .header("Access-Control-Request-Method", "TRACE"))
        .andExpect(status().isForbidden());
    mvc.perform(
            options("/api/v1/test/ping")
                .header("Origin", ALLOWED_ORIGIN)
                .header("Access-Control-Request-Method", "GET")
                .header("Access-Control-Request-Headers", "x-cabecera-rara"))
        .andExpect(status().isForbidden());
  }

  @TestConfiguration(proxyBeanMethods = false)
  static class TestEndpoints {
    @Bean
    PingController pingController() {
      return new PingController();
    }
  }

  @RestController
  @TestComponent
  static class PingController {
    @GetMapping("/api/v1/test/ping")
    Map<String, Object> ping(HttpServletRequest request) {
      return Map.of("ok", CurrentActor.from(request).isPresent());
    }
  }
}
