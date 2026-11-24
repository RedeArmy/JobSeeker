package com.usac.jobseeker.app.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
 * De la edición de un perfil al vector, de punta a punta (TDD de matching, sub-paso 3): la API de
 * `cv`, la bandeja real, el consumidor de `matching`, la cola, el trabajador y un Ollama de prueba.
 * Comprueba lo que las pruebas por capas no pueden: que las piezas encajan, que al modelo solo
 * llega texto redactado, que un perfil oculto o anonimizado deja de ser recuperable y que un Ollama
 * caído no pierde el trabajo.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
@Import(InfrastructureTestConfiguration.class)
@com.usac.jobseeker.app.infrastructure.outbox.WithoutOutboxScheduler
class MatchingEmbeddingEndToEndIntegrationTest {

  private static final String PROFILE = "/api/v1/me/professional-profile";
  private static final String CANDIDATE = "/api/v1/me/candidate-profile";

  private static final StubIssuer ISSUER = startIssuer();
  private static final java.util.concurrent.atomic.AtomicInteger OLLAMA_CALLS =
      new java.util.concurrent.atomic.AtomicInteger();
  private static final java.util.List<String> OLLAMA_BODIES =
      new java.util.concurrent.CopyOnWriteArrayList<>();
  private static volatile int ollamaStatus = 200;
  private static final com.sun.net.httpserver.HttpServer OLLAMA = startOllama();
  private static final TestTokens KEYS = TestTokens.withKey("kid-matching");

  private static com.sun.net.httpserver.HttpServer startOllama() {
    try {
      var server =
          com.sun.net.httpserver.HttpServer.create(
              new java.net.InetSocketAddress("localhost", 0), 0);
      server.createContext(
          "/api/embed",
          exchange -> {
            OLLAMA_BODIES.add(
                new String(
                    exchange.getRequestBody().readAllBytes(),
                    java.nio.charset.StandardCharsets.UTF_8));
            OLLAMA_CALLS.incrementAndGet();
            String vector =
                "[" + String.join(",", java.util.Collections.nCopies(1024, "0.001")) + "]";
            byte[] answer =
                ("{\"embeddings\":[" + vector + "]}")
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(ollamaStatus, ollamaStatus == 200 ? answer.length : -1);
            if (ollamaStatus == 200) {
              exchange.getResponseBody().write(answer);
            }
            exchange.close();
          });
      server.start();
      return server;
    } catch (java.io.IOException failure) {
      throw new IllegalStateException(failure);
    }
  }

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
    registry.add("jobseeker.matching.embedding.host", () -> "localhost");
    registry.add("jobseeker.matching.embedding.port", () -> OLLAMA.getAddress().getPort());
    registry.add("jobseeker.matching.queue.window", () -> "0s");
  }

  @AfterAll
  static void stop() {
    ISSUER.close();
    OLLAMA.stop(0);
  }

  @Autowired private MockMvc mvc;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private MeterRegistry meters;

  @Autowired
  private java.util.List<com.usac.jobseeker.common.application.outbox.OutboxEventConsumer>
      consumers;

  @Autowired
  private com.usac.jobseeker.matching.application.usecase.embedentity.ProcessEmbeddingQueueUseCase
      worker;

  @Autowired
  private com.usac.jobseeker.matching.infrastructure.adapter.out.persistence.JdbcEmbeddingRepository
      embeddings;

  @Autowired
  private com.usac.jobseeker.cv.application.usecase.anonymizeprofiledata.AnonymizeProfileDataUseCase
      anonymize;

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

  private static final String SECRET_NAME = "Ana Lopez Secretisima";
  private static final String SECRET_EMAIL = "secreta@example.test";
  private static final String SECRET_PHONE = "5555-0000";

  @org.junit.jupiter.api.BeforeEach
  void resetTheWorld() {
    OLLAMA_CALLS.set(0);
    OLLAMA_BODIES.clear();
    ollamaStatus = 200;
    jdbc.update("delete from outbox_events");
    jdbc.update("delete from matching_work_queue");
  }

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
                "{\"company\":\"Empresa Confidencial SA\",\"title\":\"Engineer\",\"startDate\":\"2020-01-01\",\"description\":\"Escríbeme a "
                    + SECRET_EMAIL
                    + " o al "
                    + SECRET_PHONE
                    + "\",\"version\":1}"))
        .andExpect(status().isCreated());
    mvc.perform(
            json(
                put(CANDIDATE),
                id,
                "{\"contactEmail\":\""
                    + SECRET_EMAIL
                    + "\",\"contactPhone\":\"+502 "
                    + SECRET_PHONE
                    + "\",\"countryOfResidence\":\"GT\",\"version\":0}"))
        .andExpect(status().isCreated());
    mvc.perform(
            json(
                put(CANDIDATE + "/skills"),
                id,
                "{\"skills\":[{\"name\":\"Java\",\"level\":4}],\"version\":1}"))
        .andExpect(status().isOk());
    return id;
  }

  /** Despacha la bandeja con los consumidores reales, entre ellos el de `matching`. */
  private void deliverEvents() {
    new com.usac.jobseeker.app.infrastructure.outbox.OutboxPoller(
            store,
            consumers,
            new com.usac.jobseeker.app.infrastructure.outbox.OutboxProperties(
                java.time.Duration.ofSeconds(1),
                100,
                3,
                java.time.Duration.ofMillis(1),
                java.time.Duration.ofMillis(5)),
            new org.springframework.transaction.support.TransactionTemplate(transactions),
            meters)
        .drain(50);
  }

  /** Vacía la cola de embeddings como lo haría el trabajador programado. */
  private void runWorker() {
    for (int pass = 0; pass < 20 && worker.execute() > 0; pass++) {
      // Cada pasada reclama lo que esté listo.
    }
  }

  private Boolean hasVector(UUID account) {
    return jdbc.queryForObject(
        "select count(*) > 0 from profile_embeddings e join profile_embedding_accounts a"
            + " on a.candidate_profile_id = e.candidate_profile_id where a.account_id = ? and e.embedding is not null",
        Boolean.class,
        account);
  }

  private Boolean discoverableCopy(UUID account) {
    return jdbc.queryForObject(
        "select e.discoverable from profile_embeddings e join profile_embedding_accounts a"
            + " on a.candidate_profile_id = e.candidate_profile_id where a.account_id = ?",
        Boolean.class,
        account);
  }

  @Test
  void anEditedProfileEndsUpWithAVectorAndTheModelOnlySawRedactedText() throws Exception {
    UUID id = candidateWithEverything();

    deliverEvents();
    runWorker();

    assertThat(hasVector(id)).isTrue();
    assertThat(discoverableCopy(id)).isTrue();
    assertThat(OLLAMA_CALLS.get()).isEqualTo(1);
    String sent = OLLAMA_BODIES.get(0);
    assertThat(sent)
        .doesNotContain(
            SECRET_NAME,
            SECRET_EMAIL,
            SECRET_PHONE,
            "Empresa Confidencial",
            "Mi biografia privada");
    assertThat(sent).contains("Java", "Engineer", "[correo]", "\"truncate\":false");
  }

  @Test
  void aBurstOfEditsIsOneModelCallBecauseTheContentIsReadWhenTheJobRuns() throws Exception {
    UUID id = candidateWithEverything();
    mvc.perform(
            json(
                put(CANDIDATE + "/skills"),
                id,
                "{\"skills\":[{\"name\":\"Java\"},{\"name\":\"SQL\"}],\"version\":2}"))
        .andExpect(status().isOk());
    mvc.perform(
            json(
                put(CANDIDATE + "/skills"),
                id,
                "{\"skills\":[{\"name\":\"Java\"},{\"name\":\"SQL\"},{\"name\":\"Go\"}],\"version\":3}"))
        .andExpect(status().isOk());

    deliverEvents();
    runWorker();

    assertThat(OLLAMA_CALLS.get()).isEqualTo(1);
    assertThat(OLLAMA_BODIES.get(0)).contains("Go", "SQL");
  }

  @Test
  void aChangeOfStructuredDataOnlyDoesNotCallTheModelAgain() throws Exception {
    UUID id = candidateWithEverything();
    deliverEvents();
    runWorker();
    assertThat(OLLAMA_CALLS.get()).isEqualTo(1);

    mvc.perform(
            json(
                put(CANDIDATE),
                id,
                "{\"contactEmail\":\""
                    + SECRET_EMAIL
                    + "\",\"countryOfResidence\":\"MX\",\"desiredModality\":\"REMOTE\",\"version\":2}"))
        .andExpect(status().isOk());
    deliverEvents();
    runWorker();

    assertThat(OLLAMA_CALLS.get()).as("el país y la modalidad no se embeben").isEqualTo(1);
  }

  @Test
  void hidingTheProfileTakesItOutOfTheSuggestionsButKeepsItsVector() throws Exception {
    UUID id = candidateWithEverything();
    deliverEvents();
    runWorker();
    float[] query = new float[1024];
    query[0] = 1f;
    var queryVector = com.usac.jobseeker.matching.domain.model.EmbeddingVector.of(query);
    UUID profileId =
        jdbc.queryForObject(
            "select candidate_profile_id from profile_embedding_accounts where account_id = ?",
            UUID.class,
            id);
    assertThat(embeddings.nearestDiscoverable(queryVector, "qwen3-embedding:0.6b", 1000))
        .extracting(n -> n.candidate().value())
        .as("antes: sí es sugerible")
        .contains(profileId);

    mvc.perform(
            json(put(CANDIDATE + "/discoverability"), id, "{\"discoverable\":false,\"version\":2}"))
        .andExpect(status().isOk());
    deliverEvents();
    runWorker();

    assertThat(discoverableCopy(id)).isFalse();
    assertThat(hasVector(id)).isTrue();
    assertThat(embeddings.nearestDiscoverable(queryVector, "qwen3-embedding:0.6b", 1000))
        .extracting(n -> n.candidate().value())
        .as("después: ya no")
        .doesNotContain(profileId);
  }

  @Test
  void anonymizingDeletesTheVectorAtOnceAndALateUpdateCannotBringItBack() throws Exception {
    UUID id = candidateWithEverything();
    deliverEvents();
    runWorker();
    assertThat(hasVector(id)).isTrue();

    anonymize.execute(
        new com.usac.jobseeker.cv.application.usecase.anonymizeprofiledata
            .AnonymizeProfileDataCommand(new com.usac.jobseeker.common.domain.model.AccountId(id)));
    // Un `profile.updated` tardío para la misma cuenta, como lo dejaría una carrera de entrega.
    store.publish(
        new com.usac.jobseeker.common.application.outbox.OutboxEvent(
            UUID.randomUUID(),
            "professional_profile",
            UUID.randomUUID(),
            99,
            "profile.updated",
            1,
            "{\"accountId\":\"" + id + "\",\"change\":\"identity\",\"version\":99}",
            null));
    deliverEvents();
    runWorker();

    assertThat(hasVector(id)).isFalse();
    assertThat(
            jdbc.queryForObject(
                "select count(*) from matching_work_queue where entity_key = ?", Integer.class, id))
        .as("nada pendiente")
        .isZero();
    assertThat(
            jdbc.queryForObject(
                "select anonymized_at is not null from profile_embedding_accounts where account_id = ?",
                Boolean.class,
                id))
        .isTrue();
  }

  @Test
  void aModelThatIsDownKeepsTheJobAndTheVectorAppearsWhenItComesBack() throws Exception {
    UUID id = candidateWithEverything();
    ollamaStatus = 503;

    deliverEvents();
    runWorker();

    assertThat(hasVector(id)).isFalse();
    assertThat(
            jdbc.queryForObject(
                "select attempts from matching_work_queue where entity_key = ?", Integer.class, id))
        .isOne();

    ollamaStatus = 200;
    jdbc.update(
        "update matching_work_queue set not_before = now() - interval '1 second' where entity_key = ?",
        id);
    runWorker();

    assertThat(hasVector(id)).isTrue();
    assertThat(
            jdbc.queryForObject(
                "select count(*) from matching_work_queue where entity_key = ?", Integer.class, id))
        .isZero();
  }

  @Test
  void anAccountThatIsNotACandidateNeverReachesTheModel() throws Exception {
    UUID id = recruiter();
    mvc.perform(json(put(PROFILE), id, "{\"fullName\":\"Reclutadora\",\"version\":0}"))
        .andExpect(status().isCreated());

    deliverEvents();
    runWorker();

    assertThat(OLLAMA_CALLS.get()).isZero();
  }
}
