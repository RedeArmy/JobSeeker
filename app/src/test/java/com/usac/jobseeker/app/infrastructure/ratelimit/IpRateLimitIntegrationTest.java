package com.usac.jobseeker.app.infrastructure.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.usac.jobseeker.app.InfrastructureTestConfiguration;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * Las políticas por IP (ADR-0011) con la cadena real: el login y el receptor de webhooks, que no
 * tienen cuenta que identificar. Cierran la deuda que dejó el sub-paso 4.5: el receptor no tenía
 * límite alguno.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(InfrastructureTestConfiguration.class)
class IpRateLimitIntegrationTest {

  private static final String SECRET = "whsec-limite";
  private static final int BURST = 3;

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("jobseeker.ratelimit.enabled", () -> "true");
    registry.add("jobseeker.ratelimit.policies.webhook.capacity", () -> Integer.toString(BURST));
    registry.add(
        "jobseeker.ratelimit.policies.webhook.refill-tokens", () -> Integer.toString(BURST));
    registry.add("jobseeker.ratelimit.policies.login.capacity", () -> "2");
    registry.add("jobseeker.ratelimit.policies.login.refill-tokens", () -> "2");
    registry.add("jobseeker.auth.webhook.secret", () -> SECRET);
  }

  @Autowired private MockMvc mvc;
  @Autowired private JdbcTemplate jdbc;

  private static RequestPostProcessor from(String address) {
    return request -> {
      request.setRemoteAddr(address);
      return request;
    };
  }

  private int signedDelivery(String address, UUID account) throws Exception {
    String json =
        "{\"accountId\":{\"value\":\"%s\"},\"occurredAt\":\"%s\"}"
            .formatted(account, Instant.now());
    byte[] body = json.getBytes(StandardCharsets.UTF_8);
    return mvc.perform(
            post("/api/v1/webhooks/clavaris")
                .with(from(address))
                .content(body)
                .header("Clavaris-Signature", WebhookSignerForTests.header(body, SECRET))
                .header("Clavaris-Event-Type", "account.suspended")
                .header("Clavaris-Event-Id", UUID.randomUUID().toString()))
        .andReturn()
        .getResponse()
        .getStatus();
  }

  @Test
  void theWebhookReceiverStopsAcceptingFromAnAddressPastItsBurstEvenWithValidSignatures()
      throws Exception {
    UUID account = UUID.randomUUID();
    jdbc.update(
        "insert into accounts (id, is_candidate_enabled, is_recruiter_enabled) values (?, true, false)",
        account);

    for (int i = 0; i < BURST; i++) {
      assertThat(signedDelivery("10.20.0.1", account)).as("entrega %d", i + 1).isEqualTo(204);
    }

    assertThat(signedDelivery("10.20.0.1", account)).isEqualTo(429);
    assertThat(signedDelivery("10.20.0.2", account)).as("otra IP, otro cubo").isEqualTo(204);
  }

  @Test
  void aFloodOfForgedDeliveriesIsLimitedBeforeTheirSignatureIsEvenChecked() throws Exception {
    List<Integer> statuses = new java.util.ArrayList<>();
    for (int i = 0; i < BURST + 2; i++) {
      statuses.add(
          mvc.perform(
                  post("/api/v1/webhooks/clavaris")
                      .with(from("10.20.0.3"))
                      .content("{}")
                      .header("Clavaris-Signature", "t=1,v1=" + "0".repeat(64)))
              .andReturn()
              .getResponse()
              .getStatus());
    }

    assertThat(statuses).containsExactly(401, 401, 401, 429, 429);
  }

  @Test
  void theLoginEntryPointIsLimitedPerAddress() throws Exception {
    int first =
        mvc.perform(get("/oauth2/authorization/clavaris").with(from("10.20.0.4")))
            .andReturn()
            .getResponse()
            .getStatus();
    int second =
        mvc.perform(get("/oauth2/authorization/clavaris").with(from("10.20.0.4")))
            .andReturn()
            .getResponse()
            .getStatus();
    var third =
        mvc.perform(get("/oauth2/authorization/clavaris").with(from("10.20.0.4")))
            .andReturn()
            .getResponse();

    assertThat(first).isNotEqualTo(429);
    assertThat(second).isNotEqualTo(429);
    assertThat(third.getStatus()).isEqualTo(429);
    assertThat(third.getContentAsString()).contains("RATE_LIMIT_EXCEEDED");
    assertThat(third.getHeader("Retry-After")).isNotBlank();
  }

  @Test
  void operationalEndpointsAreNeverLimited() throws Exception {
    for (int i = 0; i < 20; i++) {
      assertThat(
              mvc.perform(get("/actuator/health/liveness").with(from("10.20.0.5")))
                  .andReturn()
                  .getResponse()
                  .getStatus())
          .isEqualTo(200);
    }
  }
}
