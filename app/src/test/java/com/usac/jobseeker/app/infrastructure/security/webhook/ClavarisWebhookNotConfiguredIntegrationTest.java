package com.usac.jobseeker.app.infrastructure.security.webhook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.usac.jobseeker.app.InfrastructureTestConfiguration;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/** Sin secreto el receptor está cerrado: ni siquiera una firma bien formada pasa. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(InfrastructureTestConfiguration.class)
class ClavarisWebhookNotConfiguredIntegrationTest {

  @DynamicPropertySource
  static void noSecret(DynamicPropertyRegistry registry) {
    registry.add("jobseeker.auth.webhook.secret", () -> "");
    registry.add("jobseeker.auth.webhook.previous-secret", () -> "");
  }

  @Autowired private MockMvc mvc;

  @Test
  void everyDeliveryIsRefusedWith503EvenIfItCarriesAWellFormedSignature() throws Exception {
    byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
    String forged = WebhookSigner.header(Instant.now(), body, List.of("cualquier-secreto"));

    var result =
        mvc.perform(
                post("/api/v1/webhooks/clavaris")
                    .content(body)
                    .header("Clavaris-Signature", forged)
                    .header("Clavaris-Event-Type", "account.deleted")
                    .header("Clavaris-Event-Id", java.util.UUID.randomUUID().toString()))
            .andReturn();

    assertThat(result.getResponse().getStatus()).isEqualTo(503);
    assertThat(result.getResponse().getContentAsString()).contains("WEBHOOK_NOT_CONFIGURED");
  }
}
