package com.usac.jobseeker.app.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
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
 * Descubribilidad, disponibilidad y lista de exclusión de punta a punta (sub-paso 4 del TDD de
 * `cv`): la cadena real de la API con tokens firmados, Postgres y Redis reales. Comprueba lo que
 * los dobles en memoria no pueden: capacidad, bloqueo optimista real, reemplazo de conjuntos y
 * eventos en la bandeja.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
@Import(InfrastructureTestConfiguration.class)
class CandidateVisibilityEndpointIntegrationTest {

  private static final String PROFILE = "/api/v1/me/professional-profile";
  private static final String CANDIDATE = "/api/v1/me/candidate-profile";
  private static final String EXCL = CANDIDATE + "/availability-exclusions";
  private static final String BASE = "{\"contactEmail\":\"ana@example.test\",\"version\":%d}";

  private static final StubIssuer ISSUER = startIssuer();
  private static final TestTokens KEYS = TestTokens.withKey("kid-visibility");

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

  @Autowired
  private com.usac.jobseeker.cv.application.usecase.getvisibleavailability
          .GetVisibleAvailabilityUseCase
      visibleAvailability;

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

  private UUID withProfile() throws Exception {
    UUID id = candidate();
    createProfile(id);
    mvc.perform(json(put(CANDIDATE), id, BASE.formatted(0))).andExpect(status().isCreated());
    return id;
  }

  private int events(UUID account, String type) {
    return jdbc.queryForObject(
        "select count(*) from outbox_events where event_type = ? and aggregate_id ="
            + " (select id from candidate_profiles where account_id = ?)",
        Integer.class,
        type,
        account);
  }

  private static String account(UUID id) {
    return "{\"accountId\":\"%s\"}".formatted(id);
  }

  @Test
  void discoverabilityIsSwitchedOffAndOnWithItsVersionAndOneEventEach() throws Exception {
    UUID id = withProfile();

    mvc.perform(
            json(put(CANDIDATE + "/discoverability"), id, "{\"discoverable\":false,\"version\":1}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.discoverable").value(false))
        .andExpect(jsonPath("$.version").value(2));
    mvc.perform(
            json(put(CANDIDATE + "/discoverability"), id, "{\"discoverable\":false,\"version\":2}"))
        .andExpect(jsonPath("$.version").value(2));
    mvc.perform(
            json(put(CANDIDATE + "/discoverability"), id, "{\"discoverable\":true,\"version\":2}"))
        .andExpect(jsonPath("$.discoverable").value(true))
        .andExpect(jsonPath("$.version").value(3));

    assertThat(events(id, "candidate.discoverability_changed")).isEqualTo(2);
  }

  @Test
  void availabilityIsDeclaredAndDoesNotTouchTheDataOrTheDiscoverability() throws Exception {
    UUID id = withProfile();

    mvc.perform(
            json(
                put(CANDIDATE + "/availability"),
                id,
                "{\"status\":\"OPEN_TO_WORK\",\"version\":1}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.availabilityStatus").value("OPEN_TO_WORK"))
        .andExpect(jsonPath("$.contactEmail").value("ana@example.test"))
        .andExpect(jsonPath("$.discoverable").value(true))
        .andExpect(jsonPath("$.version").value(2));

    assertThat(events(id, "candidate.availability_changed")).isEqualTo(1);
  }

  @Test
  void aMissingStatusFlagOrVersionIsA400AndAStaleOneIsA409() throws Exception {
    UUID id = withProfile();

    mvc.perform(json(put(CANDIDATE + "/availability"), id, "{\"version\":1}"))
        .andExpect(status().isBadRequest());
    mvc.perform(json(put(CANDIDATE + "/discoverability"), id, "{\"version\":1}"))
        .andExpect(status().isBadRequest());
    mvc.perform(json(put(CANDIDATE + "/discoverability"), id, "{\"discoverable\":false}"))
        .andExpect(status().isBadRequest());
    mvc.perform(
            json(put(CANDIDATE + "/discoverability"), id, "{\"discoverable\":false,\"version\":7}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.error.code").value("STALE_STATE"));

    assertThat(versionOf(id)).isEqualTo(1);
  }

  @Test
  void aRecruiterOnlyAccountGets403OnEveryVisibilityRoute() throws Exception {
    UUID id = recruiter();
    for (MockHttpServletRequestBuilder request :
        List.of(
            json(put(CANDIDATE + "/discoverability"), id, "{\"discoverable\":false,\"version\":1}"),
            json(put(CANDIDATE + "/availability"), id, "{\"status\":\"UNSET\",\"version\":1}"),
            authed(get(EXCL), id),
            json(post(EXCL), id, account(UUID.randomUUID())),
            authed(delete(EXCL + "/" + UUID.randomUUID()), id))) {
      mvc.perform(request)
          .andExpect(status().isForbidden())
          .andExpect(jsonPath("$.error.code").value("FORBIDDEN_CAPABILITY"));
    }
  }

  @Test
  void withoutACandidateProfileEveryVisibilityRouteIsA404() throws Exception {
    UUID id = candidate();

    mvc.perform(
            json(put(CANDIDATE + "/discoverability"), id, "{\"discoverable\":false,\"version\":0}"))
        .andExpect(status().isNotFound());
    mvc.perform(authed(get(EXCL), id)).andExpect(status().isNotFound());
    mvc.perform(json(post(EXCL), id, account(recruiter()))).andExpect(status().isNotFound());
  }

  @Test
  void anAccountIsExcludedListedAndRemovedWithoutTouchingTheProfileVersion() throws Exception {
    UUID id = withProfile();
    UUID excluded = recruiter();

    mvc.perform(json(post(EXCL), id, account(excluded)))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.exclusions[0].accountId").value(excluded.toString()));
    mvc.perform(authed(get(EXCL), id)).andExpect(jsonPath("$.exclusions.length()").value(1));
    mvc.perform(authed(delete(EXCL + "/" + excluded), id))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.exclusions.length()").value(0));

    assertThat(versionOf(id)).isEqualTo(1);
    assertThat(events(id, "candidate.availability_exclusion_changed")).isEqualTo(2);
  }

  @Test
  void aDuplicateIsA409AnUnknownAccountOrAMissingOneIsA404AndSelfOrGarbageIsA400()
      throws Exception {
    UUID id = withProfile();
    UUID excluded = recruiter();
    mvc.perform(json(post(EXCL), id, account(excluded))).andExpect(status().isCreated());

    mvc.perform(json(post(EXCL), id, account(excluded)))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.error.code").value("ALREADY_EXISTS"));
    mvc.perform(json(post(EXCL), id, account(UUID.randomUUID()))).andExpect(status().isNotFound());
    mvc.perform(authed(delete(EXCL + "/" + UUID.randomUUID()), id))
        .andExpect(status().isNotFound());
    mvc.perform(json(post(EXCL), id, account(id)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error.details[0].code").value("SELF_EXCLUSION"));
    mvc.perform(json(post(EXCL), id, "{\"accountId\":\"no-es-uuid\"}"))
        .andExpect(status().isBadRequest());
    mvc.perform(json(post(EXCL), id, "{}")).andExpect(status().isBadRequest());
    mvc.perform(authed(delete(EXCL + "/no-es-uuid"), id)).andExpect(status().isBadRequest());
  }

  @Test
  void anAccountInItsGracePeriodCanBeExcludedButAnAnonymizedOneIsTreatedAsUnknown()
      throws Exception {
    UUID id = withProfile();
    UUID inGrace = recruiter();
    UUID anonymized = recruiter();
    jdbc.update(
        "update accounts set deletion_status = 'PENDING_DELETION', deletion_requested_at = now() where id = ?",
        inGrace);
    jdbc.update(
        "update accounts set deletion_status = 'ANONYMIZED', anonymized_at = now() where id = ?",
        anonymized);

    mvc.perform(json(post(EXCL), id, account(inGrace))).andExpect(status().isCreated());
    String unknown = bodyOf404(id, UUID.randomUUID());
    String dead = bodyOf404(id, anonymized);

    // Que la cuenta nunca existió o ya no vuelve se responde exactamente igual.
    assertThat(JsonPath.<String>read(dead, "$.error.code"))
        .isEqualTo(JsonPath.<String>read(unknown, "$.error.code"));
    assertThat(JsonPath.<String>read(dead, "$.error.message"))
        .isEqualTo(JsonPath.<String>read(unknown, "$.error.message"));
  }

  private String bodyOf404(UUID owner, UUID excluded) throws Exception {
    return mvc.perform(json(post(EXCL), owner, account(excluded)))
        .andExpect(status().isNotFound())
        .andReturn()
        .getResponse()
        .getContentAsString();
  }

  @Test
  void theExclusionListOfOneCandidateIsInvisibleAndUntouchableForAnother() throws Exception {
    UUID mine = withProfile();
    UUID theirs = withProfile();
    UUID excluded = recruiter();
    mvc.perform(json(post(EXCL), mine, account(excluded))).andExpect(status().isCreated());

    mvc.perform(authed(get(EXCL), theirs)).andExpect(jsonPath("$.exclusions.length()").value(0));
    mvc.perform(authed(delete(EXCL + "/" + excluded), theirs)).andExpect(status().isNotFound());
    mvc.perform(authed(get(EXCL), mine)).andExpect(jsonPath("$.exclusions.length()").value(1));
  }

  @Test
  void anExcludedAccountSeesUnsetAndEveryoneElseSeesTheRealStatus() throws Exception {
    UUID id = withProfile();
    UUID excluded = recruiter();
    UUID other = recruiter();
    mvc.perform(
            json(
                put(CANDIDATE + "/availability"),
                id,
                "{\"status\":\"OPEN_TO_WORK\",\"version\":1}"))
        .andExpect(status().isOk());
    mvc.perform(json(post(EXCL), id, account(excluded))).andExpect(status().isCreated());

    var asExcluded =
        visibleAvailability.execute(
            new com.usac.jobseeker.cv.application.usecase.getvisibleavailability
                .GetVisibleAvailabilityQuery(
                new com.usac.jobseeker.common.domain.model.AccountId(id),
                new com.usac.jobseeker.common.domain.model.AccountId(excluded)));
    var asOther =
        visibleAvailability.execute(
            new com.usac.jobseeker.cv.application.usecase.getvisibleavailability
                .GetVisibleAvailabilityQuery(
                new com.usac.jobseeker.common.domain.model.AccountId(id),
                new com.usac.jobseeker.common.domain.model.AccountId(other)));

    assertThat(asExcluded).isEqualTo(com.usac.jobseeker.cv.domain.model.AvailabilityStatus.UNSET);
    assertThat(asOther)
        .isEqualTo(com.usac.jobseeker.cv.domain.model.AvailabilityStatus.OPEN_TO_WORK);
  }

  @Test
  void ofTwoSimultaneousAddsOfTheSameAccountExactlyOneWins() throws Exception {
    UUID id = withProfile();
    UUID excluded = recruiter();
    ExecutorService pool = Executors.newFixedThreadPool(2);
    CountDownLatch start = new CountDownLatch(1);
    List<Future<Integer>> results = new ArrayList<>();
    for (int i = 0; i < 2; i++) {
      Callable<Integer> call =
          () -> {
            start.await();
            return mvc.perform(json(post(EXCL), id, account(excluded)))
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

    assertThat(statuses).containsExactlyInAnyOrder(201, 409);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from candidate_availability_exclusions", Integer.class))
        .isGreaterThanOrEqualTo(1);
    assertThat(events(id, "candidate.availability_exclusion_changed")).isEqualTo(1);
  }
}
