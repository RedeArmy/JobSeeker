package com.usac.jobseeker.app.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
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
    // Las pruebas entregan eventos y procesan la cola a mano (deliverEvents, runWorker). Si los
    // trabajadores programados siguieran activos, podrían reservar un evento antes que la llamada
    // manual, que lo saltaría (SKIP LOCKED), y la prueba seguiría sin él: fallaba de forma
    // intermitente. Con una hora de intervalo solo corren al arrancar, cuando aún no hay datos.
    registry.add("jobseeker.outbox.poll-interval", () -> "1h");
    registry.add("jobseeker.matching.queue.poll-interval", () -> "1h");
    registry.add("jobseeker.matching.scoring.maintenance-interval", () -> "1h");
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

  @Autowired
  private com.usac.jobseeker.matching.application.usecase.migratealgorithmversion
          .MigrateAlgorithmVersionUseCase
      migrate;

  @Autowired
  private com.usac.jobseeker.matching.application.usecase.snapshotapplicationprofile
          .SnapshotApplicationProfileUseCase
      snapshotApplication;

  @Autowired private com.usac.jobseeker.app.infrastructure.outbox.OutboxStore store;
  @Autowired private org.springframework.transaction.PlatformTransactionManager transactions;

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

  @BeforeEach
  void resetTheWorld() {
    ISSUER.jwks = KEYS.jwks();
    ISSUER.jwksStatus = 200;
    OLLAMA_CALLS.set(0);
    OLLAMA_BODIES.clear();
    ollamaStatus = 200;
    jdbc.update("delete from outbox_events");
    jdbc.update("delete from matching_work_queue");
    jdbc.update("delete from profile_view_log");
    jdbc.update("delete from profile_embeddings");
    jdbc.update("delete from profile_embedding_accounts");
    jdbc.update("delete from compatibility_scores_current");
    jdbc.update("delete from compatibility_score_snapshots");
    jdbc.update("delete from application_profile_snapshots");
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
            SECRET_NAME, SECRET_EMAIL, SECRET_PHONE, "Empresa Confidencial", "Mi biografia privada")
        .contains("Java", "Engineer", "[correo]", "\"truncate\":false");
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

  // ---- vacantes (TD-028): las mismas piezas, desde la API de `job` ----

  private static final String JOBS = "/api/v1/job/postings";
  private static final String JOB_DESCRIPTION =
      "Servicios backend. Contacto: reclutador@empresa.test";

  private static String jobBody(String title, String country, int version) {
    String salary =
        "MX".equals(country)
            ? ",\"salaryMin\":1000,\"salaryMax\":1500,\"salaryCurrency\":\"USD\",\"salaryPeriod\":\"MONTH\""
            : "";
    return "{\"title\":\""
        + title
        + "\",\"description\":\""
        + JOB_DESCRIPTION
        + "\",\"modality\":\"REMOTE\",\"country\":\""
        + country
        + "\""
        + salary
        + ",\"version\":"
        + version
        + "}";
  }

  /** Un borrador con su habilidad, sin publicar: versión 2. */
  private String draftJob(UUID owner, String title) throws Exception {
    var created =
        mvc.perform(json(post(JOBS), owner, jobBody(title, "GT", 0)))
            .andExpect(status().isCreated())
            .andReturn();
    String id =
        com.jayway.jsonpath.JsonPath.read(created.getResponse().getContentAsString(), "$.id");
    mvc.perform(
            json(
                put(JOBS + "/" + id + "/requirements"),
                owner,
                "{\"requirements\":[{\"type\":\"SKILL\",\"skillName\":\"Java\",\"minLevel\":3,\"mandatory\":true}],\"version\":1}"))
        .andExpect(status().isOk());
    return id;
  }

  /** Una vacante publicada: versión 3. */
  private String publishedJob(UUID owner, String title) throws Exception {
    String id = draftJob(owner, title);
    transition(owner, id, "PUBLISHED", 2);
    return id;
  }

  private void transition(UUID owner, String id, String to, int version) throws Exception {
    mvc.perform(
            json(
                post(JOBS + "/" + id + "/transitions"),
                owner,
                "{\"to\":\"" + to + "\",\"version\":" + version + "}"))
        .andExpect(status().isOk());
  }

  private void editJob(UUID owner, String id, String title, String country, int version)
      throws Exception {
    mvc.perform(json(put(JOBS + "/" + id), owner, jobBody(title, country, version)))
        .andExpect(status().isOk());
  }

  private Boolean jobHasVector(String id) {
    return jdbc.queryForObject(
        "select count(*) > 0 from job_embeddings where job_posting_id = ?::uuid and embedding is not null",
        Boolean.class,
        id);
  }

  private Boolean jobPublishedCopy(String id) {
    return jdbc.queryForObject(
        "select published from job_embeddings where job_posting_id = ?::uuid", Boolean.class, id);
  }

  private String jobStageCopy(String id) {
    return jdbc.queryForObject(
        "select recruiting_stage from job_embeddings where job_posting_id = ?::uuid",
        String.class,
        id);
  }

  @Test
  void aPublishedJobEndsUpWithAVectorAndTheModelOnlySawRedactedText() throws Exception {
    UUID owner = recruiter();
    String id = publishedJob(owner, "Ingeniero Backend");

    deliverEvents();
    runWorker();

    assertThat(jobHasVector(id)).isTrue();
    assertThat(jobPublishedCopy(id)).isTrue();
    assertThat(jobStageCopy(id)).isEqualTo("HIRING");
    assertThat(OLLAMA_CALLS.get()).isEqualTo(1);
    String sent = OLLAMA_BODIES.get(0);
    assertThat(sent)
        .contains("Ingeniero Backend", "Java", "[correo]", "\"truncate\":false")
        .doesNotContain("reclutador@empresa.test", owner.toString());
  }

  // TDD de job §5, regla 6: un borrador no es candidato a puntuarse; ni siquiera emite evento.
  @Test
  void aDraftNeverReachesTheModelNorLeavesARow() throws Exception {
    String id = draftJob(recruiter(), "Solo borrador");

    deliverEvents();
    runWorker();

    assertThat(OLLAMA_CALLS.get()).isZero();
    assertThat(
            jdbc.queryForObject(
                "select count(*) from job_embeddings where job_posting_id = ?::uuid",
                Integer.class,
                id))
        .isZero();
  }

  // Cerrar la saca de los candidatos pero conserva el vector (los puntajes se siguen explicando), y
  // reabrir no llama al modelo: el contenido es el mismo.
  @Test
  void closingUnpublishesButKeepsTheVectorAndReopeningDoesNotCallTheModelAgain() throws Exception {
    UUID owner = recruiter();
    String id = publishedJob(owner, "Backend");
    deliverEvents();
    runWorker();
    assertThat(OLLAMA_CALLS.get()).isOne();

    transition(owner, id, "CLOSED", 3);
    deliverEvents();
    runWorker();

    assertThat(jobPublishedCopy(id)).isFalse();
    assertThat(jobHasVector(id)).isTrue();

    transition(owner, id, "PUBLISHED", 4);
    deliverEvents();
    runWorker();

    assertThat(jobPublishedCopy(id)).isTrue();
    assertThat(OLLAMA_CALLS.get()).isOne();
  }

  // BR-MATCH-03: país y salario son dimensiones estructurales; cambiarlos no recalcula el vector.
  @Test
  void aJobChangeOfStructuredDataOnlyDoesNotCallTheModelAgain() throws Exception {
    UUID owner = recruiter();
    String id = publishedJob(owner, "Backend");
    deliverEvents();
    runWorker();

    editJob(owner, id, "Backend", "MX", 3);
    deliverEvents();
    runWorker();

    assertThat(OLLAMA_CALLS.get()).isOne();
  }

  @Test
  void aChangeOfContentEmbedsAgainWithTheNewText() throws Exception {
    UUID owner = recruiter();
    String id = publishedJob(owner, "Backend");
    deliverEvents();
    runWorker();

    editJob(owner, id, "Backend Senior", "GT", 3);
    deliverEvents();
    runWorker();

    assertThat(OLLAMA_CALLS.get()).isEqualTo(2);
    assertThat(OLLAMA_BODIES.get(1)).contains("Backend Senior");
  }

  @Test
  void aBurstOfEditsIsOneModelCallWithTheLatestContent() throws Exception {
    UUID owner = recruiter();
    String id = publishedJob(owner, "Backend");
    deliverEvents();
    runWorker();

    editJob(owner, id, "Titulo uno", "GT", 3);
    editJob(owner, id, "Titulo dos", "GT", 4);
    editJob(owner, id, "Titulo final", "GT", 5);
    deliverEvents();
    runWorker();

    assertThat(OLLAMA_CALLS.get()).isEqualTo(2);
    assertThat(OLLAMA_BODIES.get(1)).contains("Titulo final").doesNotContain("Titulo uno");
  }

  // BR-MATCH-08: REVIEWING solo cambia la copia de la etapa; el vector y la publicación siguen.
  @Test
  void reviewingOnlyChangesTheStageCopyAndHiringBringsItBack() throws Exception {
    UUID owner = recruiter();
    String id = publishedJob(owner, "Backend");
    deliverEvents();
    runWorker();

    mvc.perform(
            json(
                put(JOBS + "/" + id + "/recruiting-stage"),
                owner,
                "{\"stage\":\"REVIEWING\",\"version\":3}"))
        .andExpect(status().isOk());
    deliverEvents();
    runWorker();

    assertThat(jobStageCopy(id)).isEqualTo("REVIEWING");
    assertThat(jobPublishedCopy(id)).isTrue();
    assertThat(jobHasVector(id)).isTrue();
    assertThat(OLLAMA_CALLS.get()).isOne();

    mvc.perform(
            json(
                put(JOBS + "/" + id + "/recruiting-stage"),
                owner,
                "{\"stage\":\"HIRING\",\"version\":4}"))
        .andExpect(status().isOk());
    deliverEvents();
    runWorker();

    assertThat(jobStageCopy(id)).isEqualTo("HIRING");
  }

  @Test
  void aModelThatIsDownKeepsTheJobAndTheJobVectorAppearsWhenItComesBack() throws Exception {
    String id = publishedJob(recruiter(), "Backend");
    ollamaStatus = 503;

    deliverEvents();
    runWorker();

    assertThat(jobHasVector(id)).isFalse();
    assertThat(jobPublishedCopy(id)).isTrue();
    assertThat(
            jdbc.queryForObject(
                "select attempts from matching_work_queue where entity_key = ?::uuid",
                Integer.class,
                id))
        .isOne();

    ollamaStatus = 200;
    jdbc.update(
        "update matching_work_queue set not_before = now() - interval '1 second' where entity_key = ?::uuid",
        id);
    runWorker();

    assertThat(jobHasVector(id)).isTrue();
    assertThat(
            jdbc.queryForObject(
                "select count(*) from matching_work_queue where entity_key = ?::uuid",
                Integer.class,
                id))
        .isZero();
  }

  // Perfiles y vacantes comparten cola y trabajador: una pasada atiende las dos.
  @Test
  void aProfileAndAJobInTheSameQueueAreBothEmbeddedByTheSameWorker() throws Exception {
    UUID candidateId = candidateWithEverything();
    String jobId = publishedJob(recruiter(), "Backend");

    deliverEvents();
    runWorker();

    assertThat(hasVector(candidateId)).isTrue();
    assertThat(jobHasVector(jobId)).isTrue();
    assertThat(OLLAMA_CALLS.get()).isEqualTo(2);
  }

  // ---- candidatos sugeridos (REQ-R-10, REQ-R-11) ----

  private static final String ERROR_CODE = "$.error.code";

  private String suggestionsOf(String jobId) {
    return "/api/v1/matching/jobs/" + jobId + "/suggestions";
  }

  private UUID recruiterWith(String verification) {
    UUID id = recruiter();
    jdbc.update("update accounts set recruiter_verification = ? where id = ?", verification, id);
    return id;
  }

  /**
   * Un candidato descubrible con vector y una vacante publicada con vector, de un dueño verificado.
   */
  private record World(UUID candidate, UUID profileId, UUID owner, String jobId) {}

  private World worldWithAVerifiedOwner() throws Exception {
    UUID candidateId = candidateWithEverything();
    UUID owner = recruiterWith("VERIFIED");
    String jobId = publishedJob(owner, "Backend");
    deliverEvents();
    runWorker();
    UUID profileId =
        jdbc.queryForObject(
            "select candidate_profile_id from profile_embedding_accounts where account_id = ?",
            UUID.class,
            candidateId);
    return new World(candidateId, profileId, owner, jobId);
  }

  private int viewLogRows() {
    return jdbc.queryForObject("select count(*) from profile_view_log", Integer.class);
  }

  @Test
  void anUnverifiedRecruiterSeesNoCandidatesAndNothingIsLogged() throws Exception {
    World world = worldWithAVerifiedOwner();
    jdbc.update(
        "update accounts set recruiter_verification = 'UNVERIFIED' where id = ?", world.owner());

    mvc.perform(authed(get(suggestionsOf(world.jobId())), world.owner()))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath(ERROR_CODE).value("RECRUITER_NOT_VERIFIED"));

    assertThat(viewLogRows()).isZero();
  }

  @Test
  void aSuspendedRecruiterSeesNoCandidates() throws Exception {
    World world = worldWithAVerifiedOwner();
    jdbc.update(
        "update accounts set recruiter_verification = 'SUSPENDED' where id = ?", world.owner());

    mvc.perform(authed(get(suggestionsOf(world.jobId())), world.owner()))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath(ERROR_CODE).value("RECRUITER_SUSPENDED"));
  }

  @Test
  void aVerifiedOwnerSeesTheCandidateWithItsScoreBreakdownAndNoPersonalData() throws Exception {
    World world = worldWithAVerifiedOwner();

    String body =
        mvc.perform(authed(get(suggestionsOf(world.jobId())), world.owner()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("OK"))
            .andExpect(jsonPath("$.calibrated").value(false))
            .andExpect(jsonPath("$.algorithmVersion").isNotEmpty())
            .andReturn()
            .getResponse()
            .getContentAsString();

    // ADR-0017: el coseno bruto nunca llega a una respuesta; BR-DATA-01 y REQ-C-07: ningún dato
    // personal.
    assertThat(body)
        .doesNotContainIgnoringCase("similarity")
        .doesNotContainIgnoringCase("cosine")
        .doesNotContainIgnoringCase("accountId")
        .doesNotContain(SECRET_NAME)
        .doesNotContain(SECRET_EMAIL)
        .doesNotContain(SECRET_PHONE)
        .doesNotContain("Empresa Confidencial");
    assertThat(
            jdbc.queryForObject(
                "select count(*) from profile_view_log where viewer_account_id = ?"
                    + " and candidate_profile_id = ? and job_posting_id = ?::uuid",
                Integer.class,
                world.owner(),
                world.profileId(),
                world.jobId()))
        .isEqualTo(1);
  }

  private static String candidateOf(UUID profileId) {
    return "$.candidates[?(@.candidateProfileId=='" + profileId + "')]";
  }

  private UUID profileOf(UUID account) {
    return jdbc.queryForObject(
        "select candidate_profile_id from profile_embedding_accounts where account_id = ?",
        UUID.class,
        account);
  }

  private void declareAvailability(UUID candidate, String status, int version) throws Exception {
    mvc.perform(
            json(
                put(CANDIDATE + "/availability"),
                candidate,
                "{\"status\":\"" + status + "\",\"version\":" + version + "}"))
        .andExpect(status().isOk());
  }

  private String suggestionsBody(UUID owner, String jobId) throws Exception {
    return mvc.perform(authed(get(suggestionsOf(jobId)), owner))
        .andExpect(status().isOk())
        .andReturn()
        .getResponse()
        .getContentAsString();
  }

  private static Object first(String body, String path) {
    Object found = com.jayway.jsonpath.JsonPath.read(body, path);
    if (found instanceof java.util.List<?> list) {
      return list.isEmpty() ? null : list.get(0);
    }
    return found;
  }

  // BR-MATCH-07: entre porcentajes iguales, OPEN_TO_WORK primero; el estado no cambia el puntaje.
  @Test
  void anOpenToWorkCandidateComesFirstAmongEqualScoresWithoutChangingTheScore() throws Exception {
    UUID plain = candidateWithEverything();
    UUID open = candidateWithEverything();
    declareAvailability(open, "OPEN_TO_WORK", 2);
    UUID owner = recruiterWith("VERIFIED");
    String jobId = publishedJob(owner, "Backend");
    deliverEvents();
    runWorker();

    String body = suggestionsBody(owner, jobId);

    String openProfile = profileOf(open).toString();
    assertThat(first(body, "$.candidates[0].candidateProfileId")).isEqualTo(openProfile);
    assertThat(first(body, "$.candidates[0].availability")).isEqualTo("OPEN_TO_WORK");
    UUID plainProfile = profileOf(plain);
    assertThat(first(body, candidateOf(plainProfile) + ".availability")).isEqualTo("UNSET");
    assertThat(first(body, candidateOf(plainProfile) + ".score.overall"))
        .isEqualTo(first(body, candidateOf(profileOf(open)) + ".score.overall"));
  }

  // BR-MATCH-09 y BR-VIS-06: quien excluyó a este reclutador se ve como UNSET, sin prioridad, y su
  // puntaje sigue siendo el normal (BR-CAND-06).
  @Test
  void aCandidateWhoExcludedTheRecruiterAppearsAsUnsetWithTheSameScore() throws Exception {
    UUID owner = recruiterWith("VERIFIED");
    UUID excluding = candidateWithEverything();
    declareAvailability(excluding, "OPEN_TO_WORK", 2);
    mvc.perform(
            json(
                post(CANDIDATE + "/availability-exclusions"),
                excluding,
                "{\"accountId\":\"" + owner + "\"}"))
        .andExpect(status().isCreated());
    UUID plain = candidateWithEverything();
    String jobId = publishedJob(owner, "Backend");
    deliverEvents();
    runWorker();

    String body = suggestionsBody(owner, jobId);

    UUID excludingProfile = profileOf(excluding);
    assertThat(first(body, candidateOf(excludingProfile) + ".availability")).isEqualTo("UNSET");
    assertThat(first(body, candidateOf(excludingProfile) + ".score.overall"))
        .isEqualTo(first(body, candidateOf(profileOf(plain)) + ".score.overall"));
  }

  // R1 (ADR-0017): quien declaró menos de la completitud mínima no entra a las listas.
  @Test
  void aCandidateWhoDeclaredNoneOfWhatTheJobAsksForIsNotSuggested() throws Exception {
    UUID complete = candidateWithEverything();
    UUID bare = candidate();
    mvc.perform(
            json(
                put(PROFILE),
                bare,
                "{\"fullName\":\"Sin Datos\",\"headline\":\"Perfil vacío\",\"bio\":\"Poco\",\"version\":0}"))
        .andExpect(status().isCreated());
    mvc.perform(
            json(
                put(CANDIDATE),
                bare,
                "{\"contactEmail\":\"vacio@example.test\",\"contactPhone\":\"+502 1111-2222\",\"countryOfResidence\":\"GT\",\"version\":0}"))
        .andExpect(status().isCreated());
    UUID owner = recruiterWith("VERIFIED");
    String jobId = publishedJob(owner, "Backend");
    deliverEvents();
    runWorker();
    assertThat(hasVector(bare)).isTrue();

    String body = suggestionsBody(owner, jobId);

    assertThat(first(body, candidateOf(profileOf(complete)) + ".candidateProfileId")).isNotNull();
    assertThat(first(body, candidateOf(profileOf(bare)) + ".candidateProfileId")).isNull();
  }

  @Test
  void seeingTheSameListAgainDoesNotSpendQuota() throws Exception {
    World world = worldWithAVerifiedOwner();
    var first =
        mvc.perform(authed(get(suggestionsOf(world.jobId())), world.owner()))
            .andExpect(status().isOk())
            .andReturn();
    int remaining =
        com.jayway.jsonpath.JsonPath.read(
            first.getResponse().getContentAsString(), "$.remainingQuota");

    mvc.perform(authed(get(suggestionsOf(world.jobId())), world.owner()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.remainingQuota").value(remaining));
  }

  @Test
  void aProfileThatHidItselfIsNotSuggested() throws Exception {
    World world = worldWithAVerifiedOwner();
    mvc.perform(
            json(
                put(CANDIDATE + "/discoverability"),
                world.candidate(),
                "{\"discoverable\":false,\"version\":2}"))
        .andExpect(status().isOk());
    deliverEvents();
    runWorker();

    mvc.perform(authed(get(suggestionsOf(world.jobId())), world.owner()))
        .andExpect(status().isOk())
        .andExpect(
            jsonPath("$.candidates[?(@.candidateProfileId=='" + world.profileId() + "')]")
                .doesNotExist());
  }

  @Test
  void aRecruiterWhoAlreadySawTheQuotaOfUniqueProfilesGetsTooManyRequests() throws Exception {
    World world = worldWithAVerifiedOwner();
    for (int i = 0; i < 200; i++) {
      jdbc.update(
          "insert into profile_view_log (id, viewer_account_id, candidate_profile_id,"
              + " job_posting_id, context) values (?, ?, ?, ?, 'SUGGESTION')",
          UUID.randomUUID(),
          world.owner(),
          UUID.randomUUID(),
          UUID.fromString(world.jobId()));
    }

    mvc.perform(authed(get(suggestionsOf(world.jobId())), world.owner()))
        .andExpect(status().isTooManyRequests())
        .andExpect(jsonPath(ERROR_CODE).value("QUOTA_EXCEEDED"));
  }

  @Test
  void aJobOfAnotherRecruiterDoesNotExistForTheCaller() throws Exception {
    World world = worldWithAVerifiedOwner();

    mvc.perform(authed(get(suggestionsOf(world.jobId())), recruiterWith("VERIFIED")))
        .andExpect(status().isNotFound());
    mvc.perform(authed(get(suggestionsOf(UUID.randomUUID().toString())), world.owner()))
        .andExpect(status().isNotFound());
    mvc.perform(authed(get(suggestionsOf("no-es-un-uuid")), world.owner()))
        .andExpect(status().isNotFound());
  }

  @Test
  void anAccountWithoutTheRecruiterCapabilityIsForbidden() throws Exception {
    World world = worldWithAVerifiedOwner();

    mvc.perform(authed(get(suggestionsOf(world.jobId())), world.candidate()))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath(ERROR_CODE).value("FORBIDDEN_CAPABILITY"));
  }

  @Test
  void aJobThatIsNotPublishedIsAConflict() throws Exception {
    UUID owner = recruiterWith("VERIFIED");
    String draft = draftJob(owner, "Borrador");

    mvc.perform(authed(get(suggestionsOf(draft)), owner))
        .andExpect(status().isConflict())
        .andExpect(jsonPath(ERROR_CODE).value("INVALID_STATE"));
  }

  // BR-MATCH-08: en REVIEWING deja de generar entradas nuevas.
  @Test
  void aJobInReviewingIsPausedAndLogsNothing() throws Exception {
    World world = worldWithAVerifiedOwner();
    jdbc.update(
        "update job_postings set recruiting_stage = 'REVIEWING' where id = ?::uuid", world.jobId());

    mvc.perform(authed(get(suggestionsOf(world.jobId())), world.owner()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("PAUSED"))
        .andExpect(jsonPath("$.candidates").isEmpty());

    assertThat(viewLogRows()).isZero();
  }

  @Test
  void aPageSizeOutOfRangeIsABadRequest() throws Exception {
    World world = worldWithAVerifiedOwner();

    mvc.perform(authed(get(suggestionsOf(world.jobId())).param("size", "0"), world.owner()))
        .andExpect(status().isBadRequest());
    mvc.perform(authed(get(suggestionsOf(world.jobId())).param("size", "51"), world.owner()))
        .andExpect(status().isBadRequest());
  }

  @Test
  void withoutATokenTheEndpointIsUnauthorized() throws Exception {
    mvc.perform(get(suggestionsOf(UUID.randomUUID().toString())))
        .andExpect(status().isUnauthorized());
  }

  // ---- puntaje del candidato, persistencia y recálculo (TD-036) ----

  private String scoreOf(String jobId) {
    return "/api/v1/matching/jobs/" + jobId + "/score";
  }

  /** Un candidato completo, una vacante publicada y los vectores de ambos ya calculados. */
  private record Pair(UUID candidate, UUID owner, String jobId, UUID profileId) {}

  private Pair candidateAndPublishedJob() throws Exception {
    UUID candidate = candidateWithEverything();
    UUID owner = recruiterWith("VERIFIED");
    String jobId = publishedJob(owner, "Backend");
    deliverEvents();
    runWorker();
    return new Pair(candidate, owner, jobId, profileOf(candidate));
  }

  private Integer storedCount() {
    return jdbc.queryForObject("select count(*) from compatibility_scores_current", Integer.class);
  }

  @Test
  void aCandidateSeesItsScoreWithTheBreakdownAndItIsStoredAsViewedByTheCandidate()
      throws Exception {
    Pair pair = candidateAndPublishedJob();

    String body =
        mvc.perform(authed(get(scoreOf(pair.jobId())), pair.candidate()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("OK"))
            .andExpect(jsonPath("$.calibrated").value(false))
            .andExpect(jsonPath("$.updating").value(false))
            .andExpect(jsonPath("$.algorithmVersion").isNotEmpty())
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertThat((java.util.List<?>) com.jayway.jsonpath.JsonPath.read(body, "$.score.dimensions"))
        .hasSize(6);
    assertThat(((Number) com.jayway.jsonpath.JsonPath.read(body, "$.score.overall")).intValue())
        .isBetween(0, 100);
    assertThat(body)
        .doesNotContainIgnoringCase("cosine")
        .doesNotContainIgnoringCase("similarity")
        .doesNotContain(SECRET_NAME)
        .doesNotContain(SECRET_EMAIL);
    assertThat(
            jdbc.queryForObject(
                "select reason from compatibility_scores_current where candidate_profile_id = ?"
                    + " and job_posting_id = ?::uuid",
                String.class,
                pair.profileId(),
                pair.jobId()))
        .isEqualTo("CANDIDATE_VIEW");
  }

  @Test
  void aJobTheCandidatesCannotSeeOrThatIsTheirOwnDoesNotExistForThem() throws Exception {
    UUID candidate = candidateWithEverything();
    UUID owner = recruiterWith("VERIFIED");
    String draft = draftJob(owner, "Borrador");
    UUID both = UUID.randomUUID();
    jdbc.update(
        "insert into accounts (id, is_candidate_enabled, is_recruiter_enabled) values (?, true, true)",
        both);
    String own = publishedJob(both, "Mi propia vacante");
    deliverEvents();
    runWorker();

    mvc.perform(authed(get(scoreOf(draft)), candidate)).andExpect(status().isNotFound());
    mvc.perform(authed(get(scoreOf(own)), both)).andExpect(status().isNotFound());
    mvc.perform(authed(get(scoreOf(UUID.randomUUID().toString())), candidate))
        .andExpect(status().isNotFound());
    mvc.perform(authed(get(scoreOf("no-es-un-uuid")), candidate)).andExpect(status().isNotFound());
    assertThat(storedCount()).isZero();
  }

  @Test
  void aRecruiterOnlyAccountIsForbiddenAndACandidateWithoutAProfileGetsAConflict()
      throws Exception {
    Pair pair = candidateAndPublishedJob();
    UUID noProfile = candidate();

    mvc.perform(authed(get(scoreOf(pair.jobId())), pair.owner()))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath(ERROR_CODE).value("FORBIDDEN_CAPABILITY"));
    mvc.perform(authed(get(scoreOf(pair.jobId())), noProfile))
        .andExpect(status().isConflict())
        .andExpect(jsonPath(ERROR_CODE).value("INVALID_STATE"));
  }

  @Test
  void withoutVectorsYetTheAnswerIsAcceptedAsPendingAndNeverAPartialScore() throws Exception {
    UUID candidate = candidateWithEverything();
    UUID owner = recruiterWith("VERIFIED");
    String jobId = publishedJob(owner, "Backend");

    mvc.perform(authed(get(scoreOf(jobId)), candidate))
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.status").value("PENDING_EMBEDDING"))
        .andExpect(jsonPath("$.updating").value(true))
        .andExpect(jsonPath("$.score").doesNotExist());

    assertThat(storedCount()).isZero();
  }

  @Test
  void whatIfDeclaringARequiredSkillIsAnsweredWithoutStoringOrChangingAnything() throws Exception {
    Pair pair = candidateAndPublishedJob();
    int before = storedCount();

    String body =
        mvc.perform(
                json(
                    post(scoreOf(pair.jobId()) + "/what-if"),
                    pair.candidate(),
                    "{\"skills\":[{\"name\":\"Kubernetes\",\"level\":3}],\"languages\":[{\"language\":\"en\",\"proficiency\":\"B2\"}]}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("OK"))
            .andExpect(jsonPath("$.current.overall").isNumber())
            .andExpect(jsonPath("$.hypothetical.overall").isNumber())
            .andReturn()
            .getResponse()
            .getContentAsString();

    int delta = ((Number) com.jayway.jsonpath.JsonPath.read(body, "$.delta")).intValue();
    int current =
        ((Number) com.jayway.jsonpath.JsonPath.read(body, "$.current.overall")).intValue();
    int hypothetical =
        ((Number) com.jayway.jsonpath.JsonPath.read(body, "$.hypothetical.overall")).intValue();
    assertThat(delta).isEqualTo(hypothetical - current);
    assertThat(storedCount()).isEqualTo(before);
  }

  @Test
  void whatIfRejectsInvalidHypothesesAndHasTheSameVisibilityRules() throws Exception {
    Pair pair = candidateAndPublishedJob();

    mvc.perform(
            json(
                post(scoreOf(pair.jobId()) + "/what-if"),
                pair.candidate(),
                "{\"skills\":[{\"name\":\" \"}]}"))
        .andExpect(status().isBadRequest());
    mvc.perform(json(post(scoreOf(pair.jobId()) + "/what-if"), pair.owner(), "{}"))
        .andExpect(status().isForbidden());
    mvc.perform(
            json(post(scoreOf(UUID.randomUUID().toString()) + "/what-if"), pair.candidate(), "{}"))
        .andExpect(status().isNotFound());
  }

  // BR-MATCH-03: editar el perfil recalcula el puntaje guardado, aunque el texto a embeber no
  // cambie.
  @Test
  void editingTheProfileRecomputesTheStoredScoreOfThatPair() throws Exception {
    Pair pair = candidateAndPublishedJob();
    mvc.perform(authed(get(scoreOf(pair.jobId())), pair.candidate())).andExpect(status().isOk());
    double before =
        jdbc.queryForObject("select overall from compatibility_scores_current", Double.class);

    // La vacante pide Java: declarar solo Go baja el puntaje de ese par.
    mvc.perform(
            json(
                put(CANDIDATE + "/skills"),
                pair.candidate(),
                "{\"skills\":[{\"name\":\"Go\",\"level\":3}],\"version\":2}"))
        .andExpect(status().isOk());
    deliverEvents();
    runWorker();

    double after =
        jdbc.queryForObject("select overall from compatibility_scores_current", Double.class);
    assertThat(after).isLessThan(before);
    assertThat(storedCount()).isEqualTo(1);
  }

  @Test
  void theSuggestionsAReruiterSawAreStoredAsCurrentScoresWithTheSuggestedReason() throws Exception {
    Pair pair = candidateAndPublishedJob();

    mvc.perform(authed(get(suggestionsOf(pair.jobId())), pair.owner())).andExpect(status().isOk());

    assertThat(
            jdbc.queryForObject(
                "select reason from compatibility_scores_current where candidate_profile_id = ?"
                    + " and job_posting_id = ?::uuid",
                String.class,
                pair.profileId(),
                pair.jobId()))
        .isEqualTo("SUGGESTED");
  }

  // BR-DATA-05: anonymizar vacía lo derivado del perfil y conserva el par, el puntaje y la versión.
  @Test
  void anonymizingTheCandidateEmptiesTheStoredBreakdownButKeepsThePairAndTheScore()
      throws Exception {
    Pair pair = candidateAndPublishedJob();
    mvc.perform(authed(get(scoreOf(pair.jobId())), pair.candidate())).andExpect(status().isOk());
    assertThat(
            jdbc.queryForObject(
                "select breakdown::text like '%explanation%' from compatibility_scores_current",
                Boolean.class))
        .isTrue();

    anonymize.execute(
        new com.usac.jobseeker.cv.application.usecase.anonymizeprofiledata
            .AnonymizeProfileDataCommand(
            new com.usac.jobseeker.common.domain.model.AccountId(pair.candidate())));
    deliverEvents();

    assertThat(storedCount()).isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "select breakdown::text from compatibility_scores_current", String.class))
        .isEqualTo("{}");
    assertThat(
            jdbc.queryForObject(
                "select anonymized_at is not null from compatibility_scores_current",
                Boolean.class))
        .isTrue();
  }

  // ADR-0017, decisión 10: la migración conserva la foto del puntaje anterior y deja el actual al
  // día.
  @Test
  void migratingAStaleVersionKeepsASnapshotOfTheOldScoreAndRefreshesTheCurrentOne()
      throws Exception {
    Pair pair = candidateAndPublishedJob();
    mvc.perform(authed(get(scoreOf(pair.jobId())), pair.candidate())).andExpect(status().isOk());
    jdbc.update(
        "update compatibility_scores_current set algorithm_version = '0|viejo|aaaaaaaa|bbbbbbbb'");

    int processed = migrate.execute();

    assertThat(processed).isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from compatibility_score_snapshots where reason = 'VERSION_CHANGED' and algorithm_version = '0|viejo|aaaaaaaa|bbbbbbbb'",
                Integer.class))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "select algorithm_version <> '0|viejo|aaaaaaaa|bbbbbbbb' from compatibility_scores_current",
                Boolean.class))
        .isTrue();
    assertThat(migrate.execute()).isZero();
  }

  // La postulación (cuando `ats` exista) guarda el perfil canónico y la foto del puntaje.
  @Test
  void snapshottingAnApplicationStoresTheCanonicalProfileAndTheScoreSnapshot() throws Exception {
    Pair pair = candidateAndPublishedJob();
    UUID application = UUID.randomUUID();

    var result =
        snapshotApplication.execute(
            new com.usac.jobseeker.matching.application.usecase.snapshotapplicationprofile
                .SnapshotApplicationProfileCommand(
                new com.usac.jobseeker.common.domain.model.AccountId(pair.candidate()),
                new com.usac.jobseeker.matching.domain.model.JobPostingId(
                    UUID.fromString(pair.jobId())),
                application,
                com.usac.jobseeker.matching.domain.model.SnapshotReason.APPLIED));

    assertThat(result.profileSnapshotted()).isTrue();
    assertThat(result.scoreSnapshotted()).isTrue();
    String text =
        jdbc.queryForObject(
            "select canonical_text from application_profile_snapshots where application_id = ?",
            String.class,
            application);
    assertThat(text)
        .doesNotContain(SECRET_NAME)
        .doesNotContain(SECRET_EMAIL)
        .doesNotContain(SECRET_PHONE);
    assertThat(jdbc.queryForObject("select reason from compatibility_scores_current", String.class))
        .isEqualTo("APPLIED");
  }

  // ---- medición de TD-039 (no es una prueba de regresión: solo corre con
  // -Dsuggestions.measure=true) ----

  /**
   * Crea muchos candidatos completos, los embebe con el Ollama simulado y mide cuánto tarda pedir
   * los sugeridos cuando la etapa 2 puntúa a todos los recuperados. Imprime una tabla Markdown para
   * el informe 12. La latencia es la de este PostgreSQL local; con la ida y vuelta de red de un
   * PostgreSQL gestionado, cada consulta cuesta más, de ahí que el informe también cuente
   * consultas.
   */
  @Test
  @org.junit.jupiter.api.condition.EnabledIfSystemProperty(
      named = "suggestions.measure",
      matches = "true")
  void measureTheCostOfScoringTheRecoveredCandidates() throws Exception {
    int count = Integer.getInteger("suggestions.measure.count", 200);
    for (int i = 0; i < count; i++) {
      candidateWithEverything();
    }
    UUID owner = recruiterWith("VERIFIED");
    String jobId = publishedJob(owner, "Backend");
    for (int round = 0; round < 40; round++) {
      deliverEvents();
      runWorker();
    }
    Integer embedded =
        jdbc.queryForObject(
            "select count(*) from profile_embeddings where embedding is not null", Integer.class);
    java.util.List<Double> millis = new java.util.ArrayList<>();
    int shown = 0;
    for (int call = 0; call < 7; call++) {
      long started = System.nanoTime();
      String body =
          mvc.perform(authed(get(suggestionsOf(jobId)).param("size", "50"), owner))
              .andExpect(status().isOk())
              .andReturn()
              .getResponse()
              .getContentAsString();
      double elapsed = (System.nanoTime() - started) / 1e6;
      shown = ((java.util.List<?>) com.jayway.jsonpath.JsonPath.read(body, "$.candidates")).size();
      if (call > 0) {
        millis.add(elapsed);
      }
    }
    millis.sort(null);
    System.out.printf(
        "%n| Candidatos con vector | Mostrados | p50 ms | máx ms |%n|---|---|---|---|%n| %d | %d | %.0f | %.0f |%n",
        embedded, shown, millis.get(millis.size() / 2), millis.get(millis.size() - 1));
    System.out.flush();
  }

  /**
   * Mide cuánto tarda el candidato en ver su puntaje, que se calcula al pedirlo (ADR-0031): lectura
   * de señales de los dos lados, similitud entre vectores, aritmética y guardado. Solo corre con
   * `-Dsuggestions.measure=true`; imprime una tabla Markdown para el informe 12.
   */
  @Test
  @org.junit.jupiter.api.condition.EnabledIfSystemProperty(
      named = "suggestions.measure",
      matches = "true")
  void measureTheCandidateScoreEndpoint() throws Exception {
    Pair pair = candidateAndPublishedJob();
    java.util.List<Double> millis = new java.util.ArrayList<>();
    for (int call = 0; call < 31; call++) {
      long started = System.nanoTime();
      mvc.perform(authed(get(scoreOf(pair.jobId())), pair.candidate())).andExpect(status().isOk());
      if (call > 0) {
        millis.add((System.nanoTime() - started) / 1e6);
      }
    }
    millis.sort(null);
    System.out.printf(
        "%n| Llamadas | p50 ms | p95 ms | máx ms |%n|---|---|---|---|%n| %d | %.0f | %.0f | %.0f |%n",
        millis.size(),
        millis.get(millis.size() / 2),
        millis.get((int) Math.ceil(millis.size() * 0.95) - 1),
        millis.get(millis.size() - 1));
    System.out.flush();
  }
}
