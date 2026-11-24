package com.usac.jobseeker.app.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.usac.jobseeker.app.InfrastructureTestConfiguration;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * El perfil de candidato de punta a punta (sub-paso 3 del TDD de `cv`): la cadena real de la API
 * con tokens firmados, Postgres y Redis reales. Comprueba lo que los dobles en memoria no pueden:
 * capacidad, bloqueo optimista real, reemplazo de conjuntos y eventos en la bandeja.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
@Import(InfrastructureTestConfiguration.class)
class CandidateProfileEndpointIntegrationTest {

  private static final String PROFILE = "/api/v1/me/professional-profile";
  private static final String CANDIDATE = "/api/v1/me/candidate-profile";
  private static final String BASE = "{\"contactEmail\":\"ana@example.test\",\"version\":%d}";

  private static final StubIssuer ISSUER = startIssuer();
  private static final TestTokens KEYS = TestTokens.withKey("kid-candidate");

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

  private MockHttpServletRequestBuilder authed(
      MockHttpServletRequestBuilder request, UUID account) {
    return request.header("Authorization", "Bearer " + tokenOf(account));
  }

  private MockHttpServletRequestBuilder json(
      MockHttpServletRequestBuilder request, UUID account, String body) {
    return authed(request, account).contentType(MediaType.APPLICATION_JSON).content(body);
  }

  private UUID candidate() {
    UUID id = UUID.randomUUID();
    jdbc.update("insert into accounts (id, is_candidate_enabled) values (?, true)", id);
    return id;
  }

  private UUID recruiter() {
    UUID id = UUID.randomUUID();
    jdbc.update("insert into accounts (id, is_recruiter_enabled) values (?, true)", id);
    return id;
  }

  /** Crea el perfil base (versión 0 → 1), que toda colección necesita. */
  private void createProfile(UUID account) throws Exception {
    mvc.perform(json(put(PROFILE), account, "{\"fullName\":\"Ana López\",\"version\":0}"))
        .andExpect(status().isCreated());
  }

  private long versionOf(UUID account) {
    return jdbc.queryForObject(
        "select version from candidate_profiles where account_id = ?", Long.class, account);
  }

  private void createCandidate(UUID account) throws Exception {
    mvc.perform(json(put(CANDIDATE), account, BASE.formatted(0))).andExpect(status().isCreated());
  }

  private int candidateEvents(UUID account) {
    return jdbc.queryForObject(
        "select count(*) from outbox_events where aggregate_type = 'candidate_profile'"
            + " and aggregate_id = (select id from candidate_profiles where account_id = ?)",
        Integer.class,
        account);
  }

  @Test
  void aCandidateWithoutTheProfessionalProfileCannotCreateTheCandidateOne() throws Exception {
    UUID id = candidate();

    mvc.perform(json(put(CANDIDATE), id, BASE.formatted(0)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error.details[0].field").value("professionalProfile"));
  }

  @Test
  void theProfileIsReportedAsNotCreatedThenCreatedThenUpdatedWithItsVersions() throws Exception {
    UUID id = candidate();
    createProfile(id);

    mvc.perform(authed(get(CANDIDATE), id))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.created").value(false))
        .andExpect(jsonPath("$.version").value(0));
    createCandidate(id);
    mvc.perform(authed(get(CANDIDATE), id))
        .andExpect(jsonPath("$.created").value(true))
        .andExpect(jsonPath("$.version").value(1))
        .andExpect(jsonPath("$.discoverable").value(true))
        .andExpect(jsonPath("$.availabilityStatus").value("UNSET"));
    mvc.perform(
            json(
                put(CANDIDATE),
                id,
                "{\"contactEmail\":\"nuevo@example.test\",\"countryOfResidence\":\"gt\",\"version\":1}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.version").value(2))
        .andExpect(jsonPath("$.countryOfResidence").value("GT"));
    assertThat(candidateEvents(id)).isEqualTo(2);
  }

  @Test
  void savingTheSameDataTwiceChangesNothingAndEmitsNoEvent() throws Exception {
    UUID id = candidate();
    createProfile(id);
    createCandidate(id);

    mvc.perform(json(put(CANDIDATE), id, BASE.formatted(1)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.version").value(1));

    assertThat(candidateEvents(id)).isEqualTo(1);
  }

  @Test
  void aRecruiterOnlyAccountGets403OnEveryCandidateRoute() throws Exception {
    UUID id = recruiter();
    for (MockHttpServletRequestBuilder request :
        List.of(
            authed(get(CANDIDATE), id),
            json(put(CANDIDATE), id, BASE.formatted(0)),
            json(put(CANDIDATE + "/skills"), id, "{\"skills\":[],\"version\":1}"),
            json(put(CANDIDATE + "/languages"), id, "{\"languages\":[],\"version\":1}"),
            json(
                put(CANDIDATE + "/work-authorizations"), id, "{\"countries\":[],\"version\":1}"))) {
      mvc.perform(request)
          .andExpect(status().isForbidden())
          .andExpect(jsonPath("$.error.code").value("FORBIDDEN_CAPABILITY"));
    }
  }

  @Test
  void aStaleVersionOrCreatingTwiceIsA409AndNeverOverwrites() throws Exception {
    UUID id = candidate();
    createProfile(id);
    createCandidate(id);

    mvc.perform(json(put(CANDIDATE), id, "{\"contactEmail\":\"otro@example.test\",\"version\":0}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.error.code").value("STALE_STATE"));
    mvc.perform(json(put(CANDIDATE), id, "{\"contactEmail\":\"otro@example.test\",\"version\":7}"))
        .andExpect(status().isConflict());

    assertThat(
            jdbc.queryForObject(
                "select contact_email from candidate_profiles where account_id = ?",
                String.class,
                id))
        .isEqualTo("ana@example.test");
  }

  @Test
  void validationReportsEveryFieldAndASalaryAmountNeedsItsCurrencyAndPeriod() throws Exception {
    UUID id = candidate();
    createProfile(id);

    mvc.perform(
            json(put(CANDIDATE), id, "{\"contactEmail\":\"mal\",\"salaryMin\":100,\"version\":0}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error.details.length()").value(3))
        .andExpect(
            jsonPath("$.error.details[*].code", org.hamcrest.Matchers.hasItem("INVALID_EMAIL")));
    mvc.perform(json(put(CANDIDATE), id, "{\"contactEmail\":\"a@b.co\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error.details[0].field").value("version"));
  }

  @Test
  void theThreeSetsAreReplacedWholeAndEachAdvancesTheCandidateVersion() throws Exception {
    UUID id = candidate();
    createProfile(id);
    createCandidate(id);

    mvc.perform(
            json(
                put(CANDIDATE + "/skills"),
                id,
                "{\"skills\":[{\"name\":\"Java\",\"level\":4},{\"name\":\"SQL\"}],\"version\":1}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.version").value(2))
        .andExpect(jsonPath("$.skills.length()").value(2));
    mvc.perform(
            json(
                put(CANDIDATE + "/languages"),
                id,
                "{\"languages\":[{\"language\":\"EN\",\"proficiency\":\"B2\"}],\"version\":2}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.version").value(3))
        .andExpect(jsonPath("$.languages[0].language").value("en"));
    mvc.perform(
            json(
                put(CANDIDATE + "/work-authorizations"),
                id,
                "{\"countries\":[\"gt\",\"MX\"],\"version\":3}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.version").value(4))
        .andExpect(jsonPath("$.workAuthorizations[0]").value("GT"))
        .andExpect(jsonPath("$.skills.length()").value(2));
    mvc.perform(json(put(CANDIDATE + "/skills"), id, "{\"skills\":[],\"version\":4}"))
        .andExpect(jsonPath("$.skills.length()").value(0))
        .andExpect(jsonPath("$.languages.length()").value(1));
    assertThat(versionOf(id)).isEqualTo(5);
  }

  @Test
  void anInvalidSetLeavesTheStoredSetAndTheVersionUntouched() throws Exception {
    UUID id = candidate();
    createProfile(id);
    createCandidate(id);
    mvc.perform(
            json(
                put(CANDIDATE + "/skills"), id, "{\"skills\":[{\"name\":\"Java\"}],\"version\":1}"))
        .andExpect(status().isOk());

    mvc.perform(
            json(
                put(CANDIDATE + "/skills"),
                id,
                "{\"skills\":[{\"name\":\"A\"},{\"name\":\"a\"}],\"version\":2}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error.details[0].field").value("skills[1].name"));
    mvc.perform(json(put(CANDIDATE + "/skills"), id, "{\"version\":2}"))
        .andExpect(status().isBadRequest());

    assertThat(versionOf(id)).isEqualTo(2);
    assertThat(jdbc.queryForObject("select count(*) from candidate_skills", Integer.class))
        .isGreaterThanOrEqualTo(1);
  }

  @Test
  void replacingASetWithTheSameContentChangesNothingAndEmitsNoEvent() throws Exception {
    UUID id = candidate();
    createProfile(id);
    createCandidate(id);
    String body = "{\"countries\":[\"GT\"],\"version\":%d}";
    mvc.perform(json(put(CANDIDATE + "/work-authorizations"), id, body.formatted(1)))
        .andExpect(status().isOk());
    int events = candidateEvents(id);

    mvc.perform(json(put(CANDIDATE + "/work-authorizations"), id, body.formatted(2)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.version").value(2));

    assertThat(candidateEvents(id)).isEqualTo(events);
  }

  @Test
  void replacingASetBeforeTheProfileExistsIsA404() throws Exception {
    UUID id = candidate();
    createProfile(id);

    mvc.perform(json(put(CANDIDATE + "/languages"), id, "{\"languages\":[],\"version\":0}"))
        .andExpect(status().isNotFound());
  }

  @Test
  void ofTwoSimultaneousSavesWithTheSameVersionExactlyOneWins() throws Exception {
    UUID id = candidate();
    createProfile(id);
    createCandidate(id);
    ExecutorService pool = Executors.newFixedThreadPool(2);
    CountDownLatch start = new CountDownLatch(1);
    List<Future<Integer>> results = new ArrayList<>();
    for (String email : List.of("uno@example.test", "dos@example.test")) {
      Callable<Integer> call =
          () -> {
            start.await();
            return mvc.perform(
                    json(
                        put(CANDIDATE),
                        id,
                        "{\"contactEmail\":\"%s\",\"version\":1}".formatted(email)))
                .andReturn()
                .getResponse()
                .getStatus();
          };
      results.add(pool.submit(call));
    }
    start.countDown();
    List<Integer> statuses = new ArrayList<>();
    for (Future<Integer> f : results) {
      statuses.add(f.get());
    }
    pool.shutdown();

    assertThat(statuses).containsExactlyInAnyOrder(200, 409);
    assertThat(versionOf(id)).isEqualTo(2);
  }
}
