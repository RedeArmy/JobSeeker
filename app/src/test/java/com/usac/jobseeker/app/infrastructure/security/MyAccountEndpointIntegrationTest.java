package com.usac.jobseeker.app.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.usac.jobseeker.app.InfrastructureTestConfiguration;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * La plantilla de un endpoint, de punta a punta (paso 5 del Sprint 0): la cadena real de la API con
 * tokens firmados de verdad, Postgres y Redis reales. Comprueba lo que todo endpoint debe tener:
 * autenticación, autorización por cuenta, límite de tasa, métrica de negocio y log estructurado.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
@Import(InfrastructureTestConfiguration.class)
class MyAccountEndpointIntegrationTest {

  private static final String URL = "/api/v1/me";
  private static final int READ_CAPACITY = 3;

  private static final StubIssuer ISSUER = startIssuer();
  private static final TestTokens KEYS = TestTokens.withKey("kid-me");

  private static StubIssuer startIssuer() {
    try {
      return new StubIssuer();
    } catch (java.io.IOException failure) {
      throw new IllegalStateException(failure);
    }
  }

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("jobseeker.auth.clavaris.issuer-uri", ISSUER::issuerUri);
    registry.add("jobseeker.auth.clavaris.client-id", () -> TestTokens.CLIENT_ID);
    registry.add("jobseeker.auth.clavaris.client-secret", () -> "secreto-de-prueba");
    // El límite de tasa se enciende aquí y con una ráfaga corta para poder agotarla.
    registry.add("jobseeker.ratelimit.enabled", () -> "true");
    registry.add(
        "jobseeker.ratelimit.policies.read.capacity", () -> Integer.toString(READ_CAPACITY));
    registry.add(
        "jobseeker.ratelimit.policies.read.refill-tokens", () -> Integer.toString(READ_CAPACITY));
  }

  @AfterAll
  static void stop() {
    ISSUER.close();
  }

  @Autowired private MockMvc mvc;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private MeterRegistry meters;

  @BeforeEach
  void publishTheKeys() {
    ISSUER.jwks = KEYS.jwks();
    ISSUER.jwksStatus = 200;
  }

  private String tokenOf(UUID account) {
    return KEYS.token(ISSUER.issuerUri(), Instant.now()).subject(account.toString()).build();
  }

  private MockHttpServletRequestBuilder me(String token) {
    return get(URL).header("Authorization", "Bearer " + token);
  }

  private void createAccount(UUID id, boolean candidate, boolean recruiter, String deletion) {
    jdbc.update(
        "insert into accounts (id, is_candidate_enabled, is_recruiter_enabled, deletion_status)"
            + " values (?, ?, ?, ?)",
        id,
        candidate,
        recruiter,
        deletion);
  }

  private double usecaseCount(String outcome) {
    var counter =
        meters
            .find("jobseeker_usecase_total")
            .tag("use_case", "get_my_account")
            .tag("outcome", outcome)
            .counter();
    return counter == null ? 0 : counter.count();
  }

  // ---- el contrato ----

  @Test
  void anAccountSeesItsOwnIdAndCapabilities() throws Exception {
    UUID id = UUID.randomUUID();
    createAccount(id, true, true, "ACTIVE");

    mvc.perform(me(tokenOf(id)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(id.toString()))
        .andExpect(jsonPath("$.capabilities[0]").value("CANDIDATE"))
        .andExpect(jsonPath("$.capabilities[1]").value("RECRUITER"))
        .andExpect(jsonPath("$.capabilities.length()").value(2));
  }

  @Test
  void aFirstTimeCallerIsProvisionedAsCandidateAndSeesThat() throws Exception {
    UUID id = UUID.randomUUID();

    mvc.perform(me(tokenOf(id)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.capabilities.length()").value(1))
        .andExpect(jsonPath("$.capabilities[0]").value("CANDIDATE"));
  }

  // ---- autorización: nadie ve la cuenta de otro ----

  @Test
  void eachCallerOnlyEverSeesTheirOwnAccountWhateverTheyAskFor() throws Exception {
    UUID candidate = UUID.randomUUID();
    UUID recruiter = UUID.randomUUID();
    createAccount(candidate, true, false, "ACTIVE");
    createAccount(recruiter, false, true, "ACTIVE");

    mvc.perform(me(tokenOf(candidate)).queryParam("id", recruiter.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(candidate.toString()))
        .andExpect(jsonPath("$.capabilities[0]").value("CANDIDATE"));
    mvc.perform(me(tokenOf(recruiter)).header("X-Account-Id", candidate.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(recruiter.toString()))
        .andExpect(jsonPath("$.capabilities[0]").value("RECRUITER"));
  }

  @Test
  void withoutATokenTheAnswerIs401WithTheStandardEnvelope() throws Exception {
    mvc.perform(get(URL))
        .andExpect(status().isUnauthorized())
        .andExpect(header().string("WWW-Authenticate", "Bearer"))
        .andExpect(jsonPath("$.error.code").value("AUTHENTICATION_REQUIRED"));
  }

  @Test
  void aTokenForAnotherApplicationIsRejected() throws Exception {
    String foreign =
        KEYS.token(ISSUER.issuerUri(), Instant.now()).audience("otra-aplicacion").build();

    mvc.perform(me(foreign)).andExpect(status().isUnauthorized());
  }

  @Test
  void anAccountPendingDeletionIsRefusedWith403() throws Exception {
    UUID id = UUID.randomUUID();
    createAccount(id, true, false, "PENDING_DELETION");

    mvc.perform(me(tokenOf(id)))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.error.code").value("ACCOUNT_PENDING_DELETION"));
  }

  @Test
  void anIdentityDeletedInClavarisIsRefusedWith403EvenWithAValidToken() throws Exception {
    UUID id = UUID.randomUUID();
    createAccount(id, true, false, "ACTIVE");
    jdbc.update("update accounts set identity_status = 'DELETED' where id = ?", id);

    mvc.perform(me(tokenOf(id)))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.error.code").value("ACCOUNT_UNAVAILABLE"));
  }

  // ---- límite de tasa ----

  @Test
  void pastTheBurstTheAnswerIs429WithTheEnvelopeAndRetryAfterAndOtherAccountsAreUnaffected()
      throws Exception {
    UUID heavy = UUID.randomUUID();
    UUID light = UUID.randomUUID();
    String heavyToken = tokenOf(heavy);

    for (int i = 0; i < READ_CAPACITY; i++) {
      mvc.perform(me(heavyToken))
          .andExpect(status().isOk())
          .andExpect(header().string("X-RateLimit-Limit", Integer.toString(READ_CAPACITY)));
    }
    MvcResult limited =
        mvc.perform(me(heavyToken))
            .andExpect(status().isTooManyRequests())
            .andExpect(jsonPath("$.error.code").value("RATE_LIMIT_EXCEEDED"))
            .andExpect(jsonPath("$.error.traceId").isNotEmpty())
            .andExpect(header().string("X-RateLimit-Remaining", "0"))
            .andReturn();

    assertThat(Long.parseLong(limited.getResponse().getHeader("Retry-After"))).isPositive();
    mvc.perform(me(tokenOf(light))).andExpect(status().isOk());
  }

  // ---- métrica de negocio y log estructurado ----

  @Test
  void theUseCaseCountsItsOutcomeAsABusinessMetric() throws Exception {
    double before = usecaseCount("found");
    UUID id = UUID.randomUUID();
    createAccount(id, true, false, "ACTIVE");

    mvc.perform(me(tokenOf(id))).andExpect(status().isOk());

    assertThat(usecaseCount("found")).isEqualTo(before + 1);
  }

  @Test
  void theStructuredLogCarriesTheTraceIdAndTheAccountIdAsFieldsButNotInTheMessage(
      CapturedOutput output) throws Exception {
    UUID id = UUID.randomUUID();
    createAccount(id, true, false, "ACTIVE");

    MvcResult result = mvc.perform(me(tokenOf(id))).andExpect(status().isOk()).andReturn();

    String traceId = result.getResponse().getHeader("X-Trace-Id");
    assertThat(traceId).isNotBlank();
    String line =
        output
            .getAll()
            .lines()
            .filter(l -> l.contains("event=account_viewed"))
            .reduce((a, b) -> b)
            .orElseThrow();
    // El id va como campo propio de la línea (lo pone el contexto, una sola vez)...
    assertThat(line)
        .contains("\"traceId\":\"" + traceId + "\"")
        .contains("\"accountId\":\"" + id + "\"")
        .contains("outcome=found");
    // ...y el mensaje del caso de uso no lo repite ni lleva otro dato de la cuenta.
    assertThat(JsonPath.<String>read(line, "$.message")).doesNotContain(id.toString());
  }
}
