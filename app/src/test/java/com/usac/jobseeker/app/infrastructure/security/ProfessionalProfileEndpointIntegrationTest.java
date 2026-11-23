package com.usac.jobseeker.app.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.usac.jobseeker.app.InfrastructureTestConfiguration;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.util.List;
import java.util.Map;
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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * El perfil profesional de punta a punta (sub-paso 1 del TDD de `cv`): la cadena real de la API con
 * tokens firmados de verdad, Postgres y Redis reales y la migración del módulo. Comprueba lo que
 * todo endpoint debe tener (autenticación, autorización por cuenta, límite de tasa, métrica y log)
 * y lo propio del perfil: versión optimista, validación sin eco del valor y evento por la bandeja.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
@Import(InfrastructureTestConfiguration.class)
class ProfessionalProfileEndpointIntegrationTest {

  private static final String URL = "/api/v1/me/professional-profile";
  private static final int WRITE_CAPACITY = 3;

  private static final StubIssuer ISSUER = startIssuer();
  private static final TestTokens KEYS = TestTokens.withKey("kid-cv");

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
    // Solo la política de escritura se acota, para poder agotarla sin afectar a las lecturas.
    registry.add("jobseeker.ratelimit.enabled", () -> "true");
    registry.add(
        "jobseeker.ratelimit.policies.write.capacity", () -> Integer.toString(WRITE_CAPACITY));
    registry.add(
        "jobseeker.ratelimit.policies.write.refill-tokens", () -> Integer.toString(WRITE_CAPACITY));
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

  private MockHttpServletRequestBuilder read(UUID account) {
    return get(URL).header("Authorization", "Bearer " + tokenOf(account));
  }

  private MockHttpServletRequestBuilder write(UUID account, String json) {
    return put(URL)
        .header("Authorization", "Bearer " + tokenOf(account))
        .contentType(MediaType.APPLICATION_JSON)
        .content(json);
  }

  private static String body(String name, String headline, String bio, Object version) {
    return "{\"fullName\":%s,\"headline\":%s,\"bio\":%s,\"version\":%s}"
        .formatted(quote(name), quote(headline), quote(bio), version);
  }

  private static String quote(String text) {
    // Un NUL se envía como el escape `\u0000`, que es como llega de verdad: un NUL crudo dentro del
    // JSON ni siquiera lo acepta el parser y nunca alcanzaría la regla del dominio.
    return text == null
        ? "null"
        : "\""
            + text.replace("\\", "\\\\").replace("\n", "\\n").replace("\u0000", "\\u0000")
            + "\"";
  }

  private UUID candidateAccount() {
    UUID id = UUID.randomUUID();
    jdbc.update("insert into accounts (id, is_candidate_enabled) values (?, true)", id);
    return id;
  }

  private UUID recruiterAccount() {
    UUID id = UUID.randomUUID();
    jdbc.update("insert into accounts (id, is_recruiter_enabled) values (?, true)", id);
    return id;
  }

  private double usecaseCount(String outcome) {
    var counter =
        meters
            .find("jobseeker_usecase_total")
            .tag("use_case", "save_professional_profile")
            .tag("outcome", outcome)
            .counter();
    return counter == null ? 0 : counter.count();
  }

  private List<Map<String, Object>> eventsOf(UUID account) {
    return jdbc.queryForList(
        "select event_type, aggregate_version, payload::text as payload, trace_id from outbox_events"
            + " where payload ->> 'accountId' = ? order by aggregate_version",
        account.toString());
  }

  // ---- el contrato ----

  @Test
  void anAccountThatNeverCreatedItsProfileSeesTheEmptyStateAndNotAnError() throws Exception {
    mvc.perform(read(candidateAccount()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.created").value(false))
        .andExpect(jsonPath("$.version").value(0))
        .andExpect(jsonPath("$.fullName").doesNotExist());
  }

  @Test
  void theFirstSaveCreatesTheProfileWith201AndTheSameProfileIsReadBack() throws Exception {
    UUID id = candidateAccount();

    mvc.perform(write(id, body("Ana López", "Backend Engineer", "Línea 1\nLínea 2", 0)))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.created").value(true))
        .andExpect(jsonPath("$.version").value(1))
        .andExpect(jsonPath("$.fullName").value("Ana López"));

    mvc.perform(read(id))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.created").value(true))
        .andExpect(jsonPath("$.fullName").value("Ana López"))
        .andExpect(jsonPath("$.headline").value("Backend Engineer"))
        .andExpect(jsonPath("$.bio").value("Línea 1\nLínea 2"))
        .andExpect(jsonPath("$.version").value(1));
  }

  @Test
  void anEditWithTheReadVersionAnswers200AndTheNextVersion() throws Exception {
    UUID id = candidateAccount();
    mvc.perform(write(id, body("Ana", null, null, 0))).andExpect(status().isCreated());

    mvc.perform(write(id, body("Ana López", "Dev", null, 1)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.version").value(2))
        .andExpect(jsonPath("$.fullName").value("Ana López"));
  }

  @Test
  void savingTheSameDataAgainChangesNothingAndEmitsNoNewEvent() throws Exception {
    UUID id = candidateAccount();
    mvc.perform(write(id, body("Ana", "Dev", null, 0))).andExpect(status().isCreated());

    mvc.perform(write(id, body("Ana", "Dev", null, 1)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.version").value(1));

    assertThat(eventsOf(id)).hasSize(1);
  }

  @Test
  void aStaleVersionIs409WithItsStableCodeAndTheOtherEditSurvives() throws Exception {
    UUID id = candidateAccount();
    mvc.perform(write(id, body("Ana", null, null, 0))).andExpect(status().isCreated());
    mvc.perform(write(id, body("Ana López", null, null, 1))).andExpect(status().isOk());

    mvc.perform(write(id, body("Pisada", null, null, 1)))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.error.code").value("STALE_STATE"))
        .andExpect(jsonPath("$.error.traceId").isNotEmpty());

    mvc.perform(read(id)).andExpect(jsonPath("$.fullName").value("Ana López"));
  }

  @Test
  void creatingTwiceIsAConflictBecauseTheSecondOneDoesNotKnowTheFirst() throws Exception {
    UUID id = candidateAccount();
    mvc.perform(write(id, body("Ana", null, null, 0))).andExpect(status().isCreated());

    mvc.perform(write(id, body("Otra", null, null, 0)))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.error.code").value("STALE_STATE"));
  }

  // ---- validación ----

  @Test
  void invalidDataIs400WithEveryFieldAndNeverTheValueThatWasSent() throws Exception {
    UUID id = candidateAccount();

    MvcResult result =
        mvc.perform(write(id, body("", "x".repeat(201), "secreto-del-usuario\u0000", 0)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
            .andExpect(jsonPath("$.error.details.length()").value(3))
            .andReturn();

    String response = result.getResponse().getContentAsString();
    assertThat(response)
        .contains("fullName", "headline", "bio", "REQUIRED", "TOO_LONG")
        .doesNotContain("secreto-del-usuario", "xxxxxxxx");
  }

  @Test
  void aMissingVersionIsAValidationErrorAndNotAServerError() throws Exception {
    mvc.perform(write(candidateAccount(), "{\"fullName\":\"Ana\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
        .andExpect(jsonPath("$.error.details[0].field").value("version"))
        .andExpect(jsonPath("$.error.details[0].code").value("REQUIRED"));
  }

  @Test
  void aMalformedBodyIs400WithTheStandardEnvelope() throws Exception {
    mvc.perform(write(candidateAccount(), "{no es json"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error.code").value("REQUEST_INVALID"));
  }

  // ---- autorización: nadie toca el perfil de otro ----

  @Test
  void eachCallerOnlyEverReadsAndWritesTheirOwnProfileWhateverTheyAskFor() throws Exception {
    UUID ana = candidateAccount();
    UUID luis = candidateAccount();
    mvc.perform(write(ana, body("Ana", null, null, 0))).andExpect(status().isCreated());

    // Un identificador en la URL, en una cabecera o en el cuerpo se ignora: la cuenta sale del
    // token.
    mvc.perform(
            read(luis)
                .queryParam("accountId", ana.toString())
                .header("X-Account-Id", ana.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.created").value(false));
    mvc.perform(
            write(
                luis, "{\"accountId\":\"%s\",\"fullName\":\"Luis\",\"version\":0}".formatted(ana)))
        .andExpect(status().isCreated());

    mvc.perform(read(ana)).andExpect(jsonPath("$.fullName").value("Ana"));
    mvc.perform(read(luis)).andExpect(jsonPath("$.fullName").value("Luis"));
  }

  @Test
  void withoutATokenTheAnswerIs401WithTheStandardEnvelope() throws Exception {
    mvc.perform(get(URL))
        .andExpect(status().isUnauthorized())
        .andExpect(header().string("WWW-Authenticate", "Bearer"))
        .andExpect(jsonPath("$.error.code").value("AUTHENTICATION_REQUIRED"));
    mvc.perform(
            put(URL).contentType(MediaType.APPLICATION_JSON).content(body("Ana", null, null, 0)))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void anAccountPendingDeletionCannotReadNorWriteItsProfile() throws Exception {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "insert into accounts (id, is_candidate_enabled, deletion_status) values (?, true, 'PENDING_DELETION')",
        id);

    mvc.perform(read(id)).andExpect(status().isForbidden());
    mvc.perform(write(id, body("Ana", null, null, 0))).andExpect(status().isForbidden());
  }

  // ---- el evento de la bandeja ----

  @Test
  void aCandidateAccountPublishesTheEventWithOnlyIdentifiersAndTheTraceOfTheRequest()
      throws Exception {
    UUID id = candidateAccount();

    MvcResult result =
        mvc.perform(write(id, body("Ana López", "Dev", "Mi biografía", 0)))
            .andExpect(status().isCreated())
            .andReturn();

    assertThat(eventsOf(id))
        .singleElement()
        .satisfies(
            event -> {
              assertThat(event)
                  .containsEntry("event_type", "profile.updated")
                  .containsEntry("aggregate_version", 1L)
                  .containsEntry("trace_id", result.getResponse().getHeader("X-Trace-Id"));
              assertThat(event.get("payload").toString()).doesNotContain("Ana", "Dev", "biografía");
            });
  }

  @Test
  void aRecruiterOnlyAccountSavesItsProfileButPublishesNoEvent() throws Exception {
    UUID id = recruiterAccount();

    mvc.perform(write(id, body("Rita", "Reclutadora", null, 0))).andExpect(status().isCreated());

    assertThat(eventsOf(id)).isEmpty();
  }

  // ---- límite de tasa, métrica y log ----

  @Test
  void pastTheBurstOfWritesTheAnswerIs429AndReadsAreUnaffected() throws Exception {
    UUID id = candidateAccount();
    mvc.perform(write(id, body("Ana", null, null, 0))).andExpect(status().isCreated());
    for (int i = 1; i < WRITE_CAPACITY; i++) {
      mvc.perform(write(id, body("Ana", null, null, 1))).andExpect(status().isOk());
    }

    mvc.perform(write(id, body("Ana", null, null, 1)))
        .andExpect(status().isTooManyRequests())
        .andExpect(jsonPath("$.error.code").value("RATE_LIMIT_EXCEEDED"))
        .andExpect(header().exists("Retry-After"));

    mvc.perform(read(id)).andExpect(status().isOk());
  }

  @Test
  void theUseCaseCountsItsOutcomesAsBusinessMetrics() throws Exception {
    double created = usecaseCount("created");
    double stale = usecaseCount("stale");
    double invalid = usecaseCount("invalid");
    double unchanged = usecaseCount("unchanged");
    UUID first = candidateAccount();
    UUID second = candidateAccount();

    // Dos cuentas, porque el límite de escrituras es por cuenta y aquí hay más de las que admite
    // una.
    mvc.perform(write(first, body("Ana", null, null, 0))).andExpect(status().isCreated());
    mvc.perform(write(first, body("Ana", null, null, 0))).andExpect(status().isConflict());
    mvc.perform(write(first, body("", null, null, 1))).andExpect(status().isBadRequest());
    mvc.perform(write(second, body("Luis", null, null, 0))).andExpect(status().isCreated());
    mvc.perform(write(second, body("Luis", null, null, 1))).andExpect(status().isOk());

    assertThat(usecaseCount("created")).isEqualTo(created + 2);
    assertThat(usecaseCount("stale")).isEqualTo(stale + 1);
    assertThat(usecaseCount("invalid")).isEqualTo(invalid + 1);
    assertThat(usecaseCount("unchanged")).isEqualTo(unchanged + 1);
  }

  @Test
  void theLogCarriesTheTraceAndTheAccountButNeverTheContentOfTheProfile(CapturedOutput output)
      throws Exception {
    UUID id = candidateAccount();

    MvcResult result =
        mvc.perform(write(id, body("Nombre-Secreto-Único", "Titular-Secreto", "Bio-Secreta", 0)))
            .andExpect(status().isCreated())
            .andReturn();

    String traceId = result.getResponse().getHeader("X-Trace-Id");
    assertThat(output.getAll())
        .contains("event=professional_profile_saved")
        .contains("\"traceId\":\"" + traceId + "\"")
        .contains("\"accountId\":\"" + id + "\"")
        .doesNotContain("Nombre-Secreto-Único", "Titular-Secreto", "Bio-Secreta");
  }
}
