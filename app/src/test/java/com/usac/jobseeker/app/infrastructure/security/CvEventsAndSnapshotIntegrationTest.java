package com.usac.jobseeker.app.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.usac.jobseeker.app.InfrastructureTestConfiguration;
import com.usac.jobseeker.common.application.outbox.OutboxEvent;
import com.usac.jobseeker.common.application.outbox.OutboxEventConsumer;
import com.usac.jobseeker.cv.application.usecase.getprofilesnapshot.ProfileSnapshot;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.util.ArrayList;
import java.util.UUID;
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
@com.usac.jobseeker.app.infrastructure.outbox.WithoutOutboxScheduler
class CvEventsAndSnapshotIntegrationTest {

  private static final String PROFILE = "/api/v1/me/professional-profile";
  private static final String CANDIDATE = "/api/v1/me/candidate-profile";

  private static final StubIssuer ISSUER = startIssuer();
  private static final TestTokens KEYS = TestTokens.withKey("kid-snapshot");

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
  private com.usac.jobseeker.cv.application.usecase.getprofilesnapshot.GetProfileSnapshotUseCase
      snapshots;

  @Autowired private com.usac.jobseeker.app.infrastructure.outbox.OutboxStore store;
  @Autowired private org.springframework.transaction.PlatformTransactionManager transactions;

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

  // Los tipos de evento que `cv` publica por la bandeja, con la forma de su carga.
  private static final java.util.Set<String> CV_EVENTS =
      java.util.Set.of(
          "profile.updated",
          "candidate.discoverability_changed",
          "candidate.availability_changed",
          "candidate.availability_exclusion_changed");

  private static final String SECRET_NAME = "Ana Lopez Secretisima";
  private static final String SECRET_EMAIL = "secreta@example.test";
  private static final String SECRET_COMPANY = "Empresa Confidencial SA";

  private UUID candidateWithEverything() throws Exception {
    UUID id = candidate();
    mvc.perform(
            json(
                put(PROFILE),
                id,
                "{\"fullName\":\""
                    + SECRET_NAME
                    + "\",\"headline\":\"Backend\",\"bio\":\"Mi biografia privada\",\"version\":0}"))
        .andExpect(status().isCreated());
    mvc.perform(
            json(
                post(PROFILE + "/experiences"),
                id,
                "{\"company\":\""
                    + SECRET_COMPANY
                    + "\",\"title\":\"Engineer\",\"startDate\":\"2020-01-01\",\"version\":1}"))
        .andExpect(status().isCreated());
    mvc.perform(
            json(
                put(CANDIDATE),
                id,
                "{\"contactEmail\":\""
                    + SECRET_EMAIL
                    + "\",\"contactPhone\":\"+502 5555-0000\",\"countryOfResidence\":\"GT\",\"desiredModality\":\"REMOTE\",\"version\":0}"))
        .andExpect(status().isCreated());
    mvc.perform(
            json(
                put(CANDIDATE + "/skills"),
                id,
                "{\"skills\":[{\"name\":\"Java\",\"level\":4}],\"version\":1}"))
        .andExpect(status().isOk());
    mvc.perform(
            json(
                put(CANDIDATE + "/availability"),
                id,
                "{\"status\":\"OPEN_TO_WORK\",\"version\":2}"))
        .andExpect(status().isOk());
    mvc.perform(
            json(put(CANDIDATE + "/discoverability"), id, "{\"discoverable\":false,\"version\":3}"))
        .andExpect(status().isOk());
    return id;
  }

  private com.usac.jobseeker.app.infrastructure.outbox.OutboxPoller poller(
      java.util.List<OutboxEvent> received) {
    var consumer =
        new OutboxEventConsumer() {
          @Override
          public String name() {
            return "contrato-cv";
          }

          @Override
          public boolean handles(String eventType) {
            return CV_EVENTS.contains(eventType);
          }

          @Override
          public void consume(OutboxEvent event) {
            received.add(event);
          }
        };
    return new com.usac.jobseeker.app.infrastructure.outbox.OutboxPoller(
        store,
        java.util.List.of(consumer),
        new com.usac.jobseeker.app.infrastructure.outbox.OutboxProperties(
            java.time.Duration.ofSeconds(1),
            100,
            3,
            java.time.Duration.ofMillis(1),
            java.time.Duration.ofMillis(5)),
        new org.springframework.transaction.support.TransactionTemplate(transactions),
        meters);
  }

  @Test
  void everyCvEventTravelsThroughTheRealPollerWithTheAgreedShapeAndNoPersonalData()
      throws Exception {
    jdbc.update("delete from outbox_events");
    UUID id = candidateWithEverything();
    UUID excluded = recruiter();
    mvc.perform(
            json(
                post(CANDIDATE + "/availability-exclusions"),
                id,
                "{\"accountId\":\"%s\"}".formatted(excluded)))
        .andExpect(status().isCreated());
    var received = new ArrayList<OutboxEvent>();

    poller(received).pollOnce();

    var mine = received.stream().filter(e -> e.payload().contains(id.toString())).toList();
    assertThat(mine).extracting(OutboxEvent::eventType).containsAll(CV_EVENTS);
    for (OutboxEvent event : mine) {
      var json =
          com.jayway.jsonpath.JsonPath.<java.util.Map<String, Object>>read(event.payload(), "$");
      assertThat(json).containsEntry("accountId", id.toString());
      assertThat(event.eventVersion()).isEqualTo(1);
      assertThat(event.aggregateType()).isIn("professional_profile", "candidate_profile");
      assertThat(event.payload())
          .doesNotContain(SECRET_NAME, SECRET_EMAIL, SECRET_COMPANY, "OPEN_TO_WORK", "Java", "GT");
    }
    assertThat(
            jdbc.queryForObject(
                "select count(*) from outbox_events where status <> 'PROCESSED'", Integer.class))
        .isZero();
  }

  @Test
  void theAggregateVersionOfEachCandidateEventNeverGoesBackwards() throws Exception {
    jdbc.update("delete from outbox_events");
    UUID id = candidateWithEverything();

    var versions =
        jdbc.queryForList(
            "select aggregate_version from outbox_events where aggregate_type = 'candidate_profile'"
                + " and aggregate_id = (select id from candidate_profiles where account_id = ?)"
                + " order by created_at, aggregate_version",
            Long.class,
            id);

    assertThat(versions).isSorted().isNotEmpty();
  }

  @Test
  void theSnapshotOverRealPostgresHasTheDataAndNoneOfThePersonalOnes() throws Exception {
    UUID id = candidateWithEverything();

    var snapshot =
        snapshots
            .execute(
                new com.usac.jobseeker.cv.application.usecase.getprofilesnapshot
                    .GetProfileSnapshotQuery(
                    new com.usac.jobseeker.common.domain.model.AccountId(id)))
            .orElseThrow();

    assertThat(snapshot.headline()).isEqualTo("Backend");
    assertThat(snapshot.experiences())
        .extracting(ProfileSnapshot.Experience::title)
        .containsExactly("Engineer");
    assertThat(snapshot.skills()).extracting(ProfileSnapshot.Skill::name).containsExactly("Java");
    assertThat(snapshot.countryOfResidence()).isEqualTo("GT");
    assertThat(snapshot.desiredModality()).isEqualTo("REMOTE");
    assertThat(snapshot.discoverable()).isFalse();
    assertThat(snapshot.toString())
        .doesNotContain(
            SECRET_NAME,
            SECRET_EMAIL,
            SECRET_COMPANY,
            "Mi biografia privada",
            "+502 5555-0000",
            "OPEN_TO_WORK");
  }

  @Test
  void anAccountWithoutTheCandidateCapabilityOrWithoutProfilesHasNoSnapshot() throws Exception {
    UUID recruiterOnly = recruiter();
    UUID candidateWithoutProfile = candidate();
    UUID switchedOff = candidateWithEverything();
    jdbc.update(
        "update accounts set is_candidate_enabled = false, is_recruiter_enabled = true where id = ?",
        switchedOff);

    for (UUID account : java.util.List.of(recruiterOnly, candidateWithoutProfile, switchedOff)) {
      assertThat(
              snapshots.execute(
                  new com.usac.jobseeker.cv.application.usecase.getprofilesnapshot
                      .GetProfileSnapshotQuery(
                      new com.usac.jobseeker.common.domain.model.AccountId(account))))
          .isEmpty();
    }
  }
}
