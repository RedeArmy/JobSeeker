package com.usac.jobseeker.app.infrastructure.security;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.usac.jobseeker.app.InfrastructureTestConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Con Clavaris inalcanzable el login responde 503 controlado, no un 500 (ADR-0020, degradación).
 */
@SpringBootTest(
    properties = {
      "jobseeker.auth.clavaris.issuer-uri=http://127.0.0.1:1/o/inalcanzable",
      "jobseeker.auth.clavaris.client-id=id",
      "jobseeker.auth.clavaris.client-secret=secreto",
      "jobseeker.auth.clavaris.connect-timeout=300ms"
    })
@AutoConfigureMockMvc
@Import(InfrastructureTestConfiguration.class)
class IdentityProviderUnavailableTest {

  @Autowired private MockMvc mvc;

  @Test
  void anApiClientGetsA503WithTheStandardEnvelopeAndRetryAfter() throws Exception {
    mvc.perform(get("/oauth2/authorization/clavaris").accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isServiceUnavailable())
        .andExpect(header().exists("Retry-After"))
        .andExpect(jsonPath("$.error.code").value("IDENTITY_PROVIDER_UNAVAILABLE"))
        .andExpect(jsonPath("$.error.traceId").isNotEmpty());
  }

  @Test
  void aBrowserGetsAReadablePageWithoutInlineScripts() throws Exception {
    mvc.perform(get("/oauth2/authorization/clavaris").accept(MediaType.TEXT_HTML))
        .andExpect(status().isServiceUnavailable())
        .andExpect(header().exists("Retry-After"))
        .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
        .andExpect(
            content().string(org.hamcrest.Matchers.containsString("No podemos iniciar sesión")))
        .andExpect(
            content()
                .string(
                    org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("<script"))));
  }

  @Test
  void theCallbackAlsoAnswers503InsteadOfFailing() throws Exception {
    mvc.perform(get("/login/oauth2/code/clavaris").param("code", "x").param("state", "y"))
        .andExpect(status().isServiceUnavailable());
  }

  @Test
  void pagesThatDoNotNeedClavarisKeepWorking() throws Exception {
    mvc.perform(get("/actuator/health/liveness")).andExpect(status().isOk());
  }
}
