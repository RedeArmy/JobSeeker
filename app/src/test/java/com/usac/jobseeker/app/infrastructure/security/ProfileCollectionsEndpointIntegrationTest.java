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
import java.util.Map;
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
 * Las cuatro colecciones del perfil de punta a punta (sub-paso 2 del TDD de `cv`): la cadena real
 * de la API con tokens firmados, Postgres y Redis reales. Comprueba lo que los dobles en memoria no
 * pueden: que una operación fallida **revierte** el avance de versión, que un elemento ajeno no se
 * toca, y que de dos escrituras simultáneas con la misma versión gana exactamente una.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
@Import(InfrastructureTestConfiguration.class)
class ProfileCollectionsEndpointIntegrationTest {

  private static final String PROFILE = "/api/v1/me/professional-profile";

  private static final StubIssuer ISSUER = startIssuer();
  private static final TestTokens KEYS = TestTokens.withKey("kid-collections");

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

  private static String experience(String company, String start, String end, long version) {
    return "{\"company\":\"%s\",\"title\":\"Backend\",\"startDate\":\"%s\",\"endDate\":%s,\"version\":%d}"
        .formatted(company, start, end == null ? "null" : "\"" + end + "\"", version);
  }

  private long versionOf(UUID account) {
    return jdbc.queryForObject(
        "select version from professional_profiles where account_id = ?", Long.class, account);
  }

  private String idOfFirst(MvcResult result, String collection) throws Exception {
    return JsonPath.read(result.getResponse().getContentAsString(), "$." + collection + "[0].id");
  }

  // ---- el ciclo completo de una colección ----

  @Test
  void anExperienceIsAddedReadReplacedAndRemovedAndEachChangeAdvancesTheVersion() throws Exception {
    UUID id = candidate();
    createProfile(id);

    MvcResult added =
        mvc.perform(
                json(post(PROFILE + "/experiences"), id, experience("Acme", "2022-03-01", null, 1)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.version").value(2))
            .andExpect(jsonPath("$.experiences.length()").value(1))
            .andExpect(jsonPath("$.experiences[0].company").value("Acme"))
            .andExpect(jsonPath("$.experiences[0].endDate").doesNotExist())
            .andReturn();
    String itemId = idOfFirst(added, "experiences");

    mvc.perform(authed(get(PROFILE), id))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.experiences[0].id").value(itemId))
        .andExpect(jsonPath("$.version").value(2));

    mvc.perform(
            json(
                put(PROFILE + "/experiences/" + itemId),
                id,
                experience("Acme Corp", "2022-03-01", "2024-06-30", 2)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.version").value(3))
        .andExpect(jsonPath("$.experiences[0].company").value("Acme Corp"))
        .andExpect(jsonPath("$.experiences[0].endDate").value("2024-06-30"));

    mvc.perform(authed(delete(PROFILE + "/experiences/" + itemId).param("version", "3"), id))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.version").value(4))
        .andExpect(jsonPath("$.experiences.length()").value(0));
  }

  @Test
  void theOtherThreeCollectionsAlsoRoundTripAndEachHasItsOwnFields() throws Exception {
    UUID id = candidate();
    createProfile(id);

    mvc.perform(
            json(
                post(PROFILE + "/education"),
                id,
                "{\"institution\":\"USAC\",\"degree\":\"Ingeniería\",\"level\":\"BACHELOR\",\"completionDate\":\"2025-12-15\",\"version\":1}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.education[0].level").value("BACHELOR"));
    mvc.perform(
            json(
                post(PROFILE + "/certifications"),
                id,
                "{\"name\":\"AWS\",\"issuingOrganization\":\"Amazon\",\"issueDate\":\"2024-01-15\",\"credentialUrl\":\"https://example.com/c/1\",\"version\":2}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.certifications[0].credentialUrl").value("https://example.com/c/1"));
    mvc.perform(
            json(
                post(PROFILE + "/projects"),
                id,
                "{\"name\":\"JobSeeker\",\"type\":\"PERSONAL\",\"role\":\"Autor\",\"startDate\":\"2026-01-10\",\"version\":3}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.version").value(4))
        .andExpect(jsonPath("$.projects[0].type").value("PERSONAL"))
        .andExpect(jsonPath("$.education.length()").value(1))
        .andExpect(jsonPath("$.certifications.length()").value(1));
  }

  @Test
  void editingTheIdentityAfterAddingItemsStillReturnsTheCollections() throws Exception {
    UUID id = candidate();
    createProfile(id);
    mvc.perform(json(post(PROFILE + "/experiences"), id, experience("Acme", "2022-03-01", null, 1)))
        .andExpect(status().isCreated());

    mvc.perform(json(put(PROFILE), id, "{\"fullName\":\"Ana María López\",\"version\":2}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.version").value(3))
        .andExpect(jsonPath("$.experiences.length()").value(1));
  }

  // ---- versión ----

  @Test
  void aStaleVersionIs409AndTheOtherEditSurvives() throws Exception {
    UUID id = candidate();
    createProfile(id);
    mvc.perform(
            json(post(PROFILE + "/experiences"), id, experience("Primera", "2022-03-01", null, 1)))
        .andExpect(status().isCreated());

    mvc.perform(
            json(post(PROFILE + "/experiences"), id, experience("Pisada", "2023-01-01", null, 1)))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.error.code").value("STALE_STATE"));

    mvc.perform(authed(get(PROFILE), id)).andExpect(jsonPath("$.experiences.length()").value(1));
  }

  @Test
  void aMissingVersionIs400WithTheFieldAndNotAServerError() throws Exception {
    UUID id = candidate();
    createProfile(id);

    mvc.perform(
            json(
                post(PROFILE + "/experiences"),
                id,
                "{\"company\":\"A\",\"title\":\"B\",\"startDate\":\"2022-03-01\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
        .andExpect(jsonPath("$.error.details[0].field").value("version"));
    mvc.perform(authed(delete(PROFILE + "/experiences/" + UUID.randomUUID()), id))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error.code").value("REQUEST_INVALID"));
  }

  @Test
  void twoWritesAtTheSameTimeWithTheSameVersionHaveExactlyOneWinner() throws Exception {
    UUID id = candidate();
    createProfile(id);
    int threads = 6;
    ExecutorService pool = Executors.newFixedThreadPool(threads);
    CountDownLatch go = new CountDownLatch(1);
    List<Future<Integer>> results = new ArrayList<>();
    try {
      for (int i = 0; i < threads; i++) {
        String company = "Simultánea " + i;
        Callable<Integer> call =
            () -> {
              go.await();
              return mvc.perform(
                      json(
                          post(PROFILE + "/experiences"),
                          id,
                          experience(company, "2022-03-01", null, 1)))
                  .andReturn()
                  .getResponse()
                  .getStatus();
            };
        results.add(pool.submit(call));
      }
      go.countDown();
      List<Integer> statuses = new ArrayList<>();
      for (Future<Integer> result : results) {
        statuses.add(result.get());
      }
      assertThat(statuses).containsOnlyOnce(201).filteredOn(s -> s != 201).containsOnly(409);
    } finally {
      pool.shutdownNow();
    }
    assertThat(
            jdbc.queryForObject(
                "select count(*) from experiences e join professional_profiles p"
                    + " on p.id = e.professional_profile_id where p.account_id = ?",
                Long.class,
                id))
        .isEqualTo(1L);
    assertThat(versionOf(id)).isEqualTo(2L);
  }

  // ---- propiedad y reversión ----

  @Test
  void anItemOfAnotherAccountIs404AndNeitherItNorTheCallersVersionChanges() throws Exception {
    UUID mine = candidate();
    UUID theirs = candidate();
    createProfile(mine);
    createProfile(theirs);
    MvcResult theirAdd =
        mvc.perform(
                json(
                    post(PROFILE + "/experiences"),
                    theirs,
                    experience("Suya", "2022-03-01", null, 1)))
            .andReturn();
    String theirItem = idOfFirst(theirAdd, "experiences");

    mvc.perform(
            json(
                put(PROFILE + "/experiences/" + theirItem),
                mine,
                experience("Robada", "2022-03-01", null, 1)))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"));
    mvc.perform(authed(delete(PROFILE + "/experiences/" + theirItem).param("version", "1"), mine))
        .andExpect(status().isNotFound());

    // La reversión real: el avance de versión que se hizo antes de descubrir que el elemento no era
    // suyo se deshizo con la transacción, así que puede seguir editando con la misma versión.
    assertThat(versionOf(mine)).isEqualTo(1L);
    mvc.perform(
            json(post(PROFILE + "/experiences"), mine, experience("Mía", "2022-03-01", null, 1)))
        .andExpect(status().isCreated());
    mvc.perform(authed(get(PROFILE), theirs))
        .andExpect(jsonPath("$.experiences[0].company").value("Suya"))
        .andExpect(jsonPath("$.version").value(2));
  }

  @Test
  void anItemThatDoesNotExistIs404AndLeavesTheVersionAlone() throws Exception {
    UUID id = candidate();
    createProfile(id);

    mvc.perform(
            json(
                put(PROFILE + "/projects/" + UUID.randomUUID()),
                id,
                "{\"name\":\"P\",\"type\":\"WORK\",\"version\":1}"))
        .andExpect(status().isNotFound());

    assertThat(versionOf(id)).isEqualTo(1L);
  }

  @Test
  void anAccountWithoutAProfileGets404WhenAddingAnItem() throws Exception {
    mvc.perform(
            json(
                post(PROFILE + "/experiences"),
                candidate(),
                experience("A", "2022-03-01", null, 0)))
        .andExpect(status().isNotFound());
  }

  // ---- validación ----

  @Test
  void invalidDataIs400WithEveryFieldAndNeverTheValueAndTheVersionDoesNotAdvance()
      throws Exception {
    UUID id = candidate();
    createProfile(id);

    MvcResult result =
        mvc.perform(
                json(
                    post(PROFILE + "/experiences"),
                    id,
                    "{\"company\":\" \",\"title\":\"secreto-del-usuario\\u0000\",\"startDate\":\"2022-03-01\",\"endDate\":\"2021-01-01\",\"version\":1}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
            .andReturn();

    String body = result.getResponse().getContentAsString();
    assertThat(body)
        .contains(
            "company", "REQUIRED", "title", "INVALID_CHARACTERS", "endDate", "END_BEFORE_START")
        .doesNotContain("secreto-del-usuario");
    assertThat(versionOf(id)).isEqualTo(1L);
  }

  @Test
  void aLinkThatIsNotAWebAddressIsRejectedBeforeItCanBeShownAsALink() throws Exception {
    UUID id = candidate();
    createProfile(id);

    mvc.perform(
            json(
                post(PROFILE + "/certifications"),
                id,
                "{\"name\":\"N\",\"issuingOrganization\":\"O\",\"credentialUrl\":\"javascript:alert(1)\",\"version\":1}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error.details[0].field").value("credentialUrl"))
        .andExpect(jsonPath("$.error.details[0].code").value("INVALID_URL"));
  }

  @Test
  void aCollectionAcceptsFiftyElementsAndRefusesTheFiftyFirst() throws Exception {
    UUID id = candidate();
    createProfile(id);
    long version = 1;
    for (int i = 0; i < 50; i++) {
      version++;
      mvc.perform(
              json(
                  post(PROFILE + "/projects"),
                  id,
                  "{\"name\":\"P%d\",\"type\":\"PERSONAL\",\"version\":%d}"
                      .formatted(i, version - 1)))
          .andExpect(status().isCreated());
    }

    mvc.perform(
            json(
                post(PROFILE + "/projects"),
                id,
                "{\"name\":\"P51\",\"type\":\"WORK\",\"version\":" + version + "}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error.details[0].field").value("projects"))
        .andExpect(jsonPath("$.error.details[0].code").value("TOO_MANY"));
    assertThat(versionOf(id)).isEqualTo(version);
  }

  @Test
  void anUnknownEnumValueOrAMalformedIdentifierIs400() throws Exception {
    UUID id = candidate();
    createProfile(id);

    mvc.perform(
            json(
                post(PROFILE + "/projects"),
                id,
                "{\"name\":\"P\",\"type\":\"FREELANCE\",\"version\":1}"))
        .andExpect(status().isBadRequest());
    mvc.perform(
            json(
                put(PROFILE + "/projects/no-es-un-uuid"),
                id,
                "{\"name\":\"P\",\"type\":\"WORK\",\"version\":1}"))
        .andExpect(status().isBadRequest());
  }

  // ---- autenticación ----

  @Test
  void withoutATokenEveryWriteIs401() throws Exception {
    mvc.perform(
            post(PROFILE + "/experiences")
                .contentType(MediaType.APPLICATION_JSON)
                .content(experience("A", "2022-03-01", null, 1)))
        .andExpect(status().isUnauthorized());
    mvc.perform(delete(PROFILE + "/experiences/" + UUID.randomUUID()).param("version", "1"))
        .andExpect(status().isUnauthorized());
  }

  // ---- evento, métrica y log ----

  @Test
  void aCandidateAccountPublishesOneEventPerChangeWithTheKindAndOnlyIdentifiers() throws Exception {
    UUID id = candidate();
    createProfile(id);

    MvcResult added =
        mvc.perform(
                json(post(PROFILE + "/experiences"), id, experience("Acme", "2022-03-01", null, 1)))
            .andReturn();
    mvc.perform(
        json(
            post(PROFILE + "/projects"),
            id,
            "{\"name\":\"JobSeeker\",\"type\":\"WORK\",\"version\":2}"));

    List<Map<String, Object>> events =
        jdbc.queryForList(
            "select aggregate_version, payload ->> 'change' as change, payload::text as payload, trace_id"
                + " from outbox_events"
                + " where payload ->> 'accountId' = ? order by aggregate_version",
            id.toString());
    assertThat(events).extracting(e -> e.get("aggregate_version")).containsExactly(1L, 2L, 3L);
    assertThat(events.get(1)).containsEntry("change", "experience");
    assertThat(events.get(1).get("payload").toString()).doesNotContain("Acme");
    assertThat(events.get(2)).containsEntry("change", "project");
    assertThat(events.get(2).get("payload").toString()).doesNotContain("JobSeeker");
    assertThat(events.get(1))
        .containsEntry("trace_id", added.getResponse().getHeader("X-Trace-Id"));
  }

  @Test
  void aRecruiterOnlyAccountEditsItsCollectionsButPublishesNoEvent() throws Exception {
    UUID id = recruiter();
    createProfile(id);

    mvc.perform(
            json(post(PROFILE + "/experiences"), id, experience("Agencia", "2022-03-01", null, 1)))
        .andExpect(status().isCreated());

    assertThat(
            jdbc.queryForObject(
                "select count(*) from outbox_events where payload ->> 'accountId' = ?",
                Long.class,
                id.toString()))
        .isZero();
  }

  @Test
  void theUseCaseCountsItsOutcomesAndTheLogNeverCarriesTheContent(CapturedOutput output)
      throws Exception {
    double added = count("manage_experiences", "added");
    double invalid = count("manage_experiences", "invalid");
    UUID id = candidate();
    createProfile(id);

    mvc.perform(
            json(
                post(PROFILE + "/experiences"),
                id,
                experience("Empresa-Secreta-Única", "2022-03-01", null, 1)))
        .andExpect(status().isCreated());
    mvc.perform(json(post(PROFILE + "/experiences"), id, experience(" ", "2022-03-01", null, 2)))
        .andExpect(status().isBadRequest());

    assertThat(count("manage_experiences", "added")).isEqualTo(added + 1);
    assertThat(count("manage_experiences", "invalid")).isEqualTo(invalid + 1);
    assertThat(output.getAll())
        .contains("event=professional_profile_collection_changed")
        .doesNotContain("Empresa-Secreta-Única");
  }

  private double count(String useCase, String outcome) {
    var counter =
        meters
            .find("jobseeker_usecase_total")
            .tag("use_case", useCase)
            .tag("outcome", outcome)
            .counter();
    return counter == null ? 0 : counter.count();
  }
}
