package com.usac.jobseeker.app.infrastructure.security;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.usac.jobseeker.app.InfrastructureTestConfiguration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Con Clavaris inalcanzable desde el arranque (ADR-0020, degradación): la aplicación arranca, y la
 * API distingue lo que es culpa del token (401) de lo que no se puede comprobar (503).
 */
@SpringBootTest(
    properties = {
      "jobseeker.auth.clavaris.issuer-uri=http://127.0.0.1:1/o/inalcanzable",
      "jobseeker.auth.clavaris.client-id=client-de-prueba",
      "jobseeker.auth.clavaris.client-secret=secreto",
      "jobseeker.auth.clavaris.connect-timeout=300ms"
    })
@AutoConfigureMockMvc
@Import(InfrastructureTestConfiguration.class)
class ApiIdentityProviderDownIntegrationTest {

  private static final TestTokens KEYS = TestTokens.withKey("kid-caida");

  @Autowired private MockMvc mvc;

  private static String wellFormedToken() {
    return KEYS.token("http://127.0.0.1:1/o/inalcanzable", Instant.now())
        .subject(UUID.randomUUID().toString())
        .build();
  }

  @Test
  void aWellFormedTokenCannotBeCheckedSoTheApiAnswers503WithRetryAfter() throws Exception {
    mvc.perform(get("/api/v1/algo").header("Authorization", "Bearer " + wellFormedToken()))
        .andExpect(status().isServiceUnavailable())
        .andExpect(header().exists("Retry-After"))
        .andExpect(jsonPath("$.error.code").value("IDENTITY_PROVIDER_UNAVAILABLE"));
  }

  @Test
  void garbageStillGets401BecauseItDoesNotNeedClavarisToBeRejected() throws Exception {
    mvc.perform(get("/api/v1/algo").header("Authorization", "Bearer basura"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.error.code").value("TOKEN_INVALID"));
  }

  @Test
  void aTokenWithAnotherAlgorithmAlsoGets401WithoutNeedingClavaris() throws Exception {
    String unsigned =
        KEYS.token("http://127.0.0.1:1/o/inalcanzable", Instant.now()).buildUnsigned();

    mvc.perform(get("/api/v1/algo").header("Authorization", "Bearer " + unsigned))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void withoutATokenItIsStillA401BecauseNothingNeedsToBeChecked() throws Exception {
    mvc.perform(get("/api/v1/algo"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.error.code").value("AUTHENTICATION_REQUIRED"));
  }

  @Test
  void theWebStillRedirectsToTheLoginSoOnlyTheLoginAndTheCheckOfTokensDependOnClavaris()
      throws Exception {
    mvc.perform(get("/app/home")).andExpect(status().is3xxRedirection());
  }
}
