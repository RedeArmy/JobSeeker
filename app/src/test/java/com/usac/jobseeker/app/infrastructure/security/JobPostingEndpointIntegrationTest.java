package com.usac.jobseeker.app.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.usac.jobseeker.auth.application.usecase.changerecruiterverification.ChangeRecruiterVerificationCommand;
import com.usac.jobseeker.auth.application.usecase.changerecruiterverification.ChangeRecruiterVerificationUseCase;
import com.usac.jobseeker.common.domain.model.AccountId;
import com.usac.jobseeker.common.domain.model.RecruiterVerification;
import com.usac.jobseeker.job.application.usecase.getjobsignals.GetJobSignalsQuery;
import com.usac.jobseeker.job.application.usecase.getjobsignals.GetJobSignalsUseCase;
import com.usac.jobseeker.job.application.usecase.getjobsignals.JobSnapshot;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * La vacante y su ciclo de vida manual de punta a punta (Ola 1 del TDD de `job`): la cadena real de
 * la API con tokens firmados, Postgres y Redis reales. Comprueba lo que los dobles en memoria no
 * pueden: la capacidad de reclutador, la visibilidad según quién pregunta, el *compare-and-set*
 * real entre dos transiciones simultáneas y los eventos en la bandeja.
 */
class JobPostingEndpointIntegrationTest extends SharedIssuerIntegrationTest {

  private static final String POSTINGS = "/api/v1/job/postings";
  private static final String BODY =
      "{\"title\":\"Backend\",\"description\":\"Servicios en Java\",\"modality\":\"REMOTE\","
          + "\"country\":\"GT\",\"salaryMin\":3000,\"salaryMax\":4500,\"salaryCurrency\":\"USD\","
          + "\"salaryPeriod\":\"MONTH\",\"version\":%d}";
  private static final String JAVA =
      "{\"requirements\":[{\"type\":\"SKILL\",\"skillName\":\"Java\","
          + "\"minLevel\":3,\"mandatory\":true}],\"version\":%d}";
  private static final String TRANSITION = "{\"to\":\"%s\",\"version\":%d}";
  private static final String ERROR_CODE = "$.error.code";
  private static final int ROUNDS = 25;

  @Autowired private MockMvc mvc;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private GetJobSignalsUseCase signals;
  @Autowired private ChangeRecruiterVerificationUseCase verification;

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

  private UUID recruiter() {
    UUID id = UUID.randomUUID();
    jdbc.update("insert into accounts (id, is_recruiter_enabled) values (?, true)", id);
    return id;
  }

  private UUID candidate() {
    UUID id = UUID.randomUUID();
    jdbc.update("insert into accounts (id, is_candidate_enabled) values (?, true)", id);
    return id;
  }

  /** Crea una vacante en borrador y devuelve su identificador. */
  private String createDraft(UUID owner) throws Exception {
    MvcResult result =
        mvc.perform(json(post(POSTINGS), owner, BODY.formatted(0)))
            .andExpect(status().isCreated())
            .andReturn();
    return JsonPath.read(result.getResponse().getContentAsString(), "$.id");
  }

  /** Una vacante publicada: borrador, una habilidad y publicar. Termina en la versión 3. */
  private String createPublished(UUID owner) throws Exception {
    String id = createDraft(owner);
    mvc.perform(json(put(POSTINGS + "/" + id + "/requirements"), owner, JAVA.formatted(1)))
        .andExpect(status().isOk());
    mvc.perform(
            json(
                post(POSTINGS + "/" + id + "/transitions"),
                owner,
                TRANSITION.formatted("PUBLISHED", 2)))
        .andExpect(status().isOk());
    return id;
  }

  private int events(String id, String type) {
    return jdbc.queryForObject(
        "select count(*) from outbox_events where event_type = ? and aggregate_id = ?::uuid",
        Integer.class,
        type,
        id);
  }

  // ---- el ciclo de vida completo ----

  @Test
  void aPostingGoesFromDraftThroughPublishCloseAndReopenWithOneEventPerStep() throws Exception {
    UUID owner = recruiter();
    MvcResult created =
        mvc.perform(json(post(POSTINGS), owner, BODY.formatted(0)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.status").value("DRAFT"))
            .andExpect(jsonPath("$.version").value(1))
            .andExpect(jsonPath("$.acceptingApplications").value(true))
            .andExpect(jsonPath("$.recruitingStage").value("HIRING"))
            .andExpect(jsonPath("$.salary.currency").value("USD"))
            .andReturn();
    String id = JsonPath.read(created.getResponse().getContentAsString(), "$.id");
    String path = POSTINGS + "/" + id;

    mvc.perform(json(put(path + "/requirements"), owner, JAVA.formatted(1)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.version").value(2))
        .andExpect(jsonPath("$.requirements[0].skillName").value("Java"))
        .andExpect(jsonPath("$.requirements[0].mandatory").value(true));
    mvc.perform(json(post(path + "/transitions"), owner, TRANSITION.formatted("PUBLISHED", 2)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("PUBLISHED"))
        .andExpect(jsonPath("$.version").value(3))
        .andExpect(jsonPath("$.publishedAt").isNotEmpty());
    mvc.perform(json(post(path + "/transitions"), owner, TRANSITION.formatted("CLOSED", 3)))
        .andExpect(jsonPath("$.status").value("CLOSED"))
        .andExpect(jsonPath("$.closedAt").isNotEmpty());
    mvc.perform(json(post(path + "/transitions"), owner, TRANSITION.formatted("PUBLISHED", 4)))
        .andExpect(jsonPath("$.status").value("PUBLISHED"))
        .andExpect(jsonPath("$.closedAt").doesNotExist());

    assertThat(events(id, "job.posting_published")).isOne();
    assertThat(events(id, "job.posting_closed")).isOne();
    assertThat(events(id, "job.posting_reopened")).isOne();
  }

  @Test
  void discardingADraftDeletesItLogicallyAndItDisappears() throws Exception {
    UUID owner = recruiter();
    String id = createDraft(owner);

    mvc.perform(
            json(
                post(POSTINGS + "/" + id + "/transitions"),
                owner,
                TRANSITION.formatted("DELETED", 1)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("DELETED"));

    mvc.perform(authed(get(POSTINGS + "/" + id), owner)).andExpect(status().isNotFound());
    assertThat(
            jdbc.queryForObject(
                "select count(*) from job_postings where id = ?::uuid", Integer.class, id))
        .isOne();
    assertThat(events(id, "job.posting_deleted")).isOne();
  }

  // ---- reglas de negocio ----

  // BR-JOB-02: sin una habilidad o formación no se publica, y la vacante no cambia.
  @Test
  void publishingWithoutAStructuredRequirementIsA400AndChangesNothing() throws Exception {
    UUID owner = recruiter();
    String id = createDraft(owner);

    mvc.perform(
            json(
                post(POSTINGS + "/" + id + "/transitions"),
                owner,
                TRANSITION.formatted("PUBLISHED", 1)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath(ERROR_CODE).value("VALIDATION_FAILED"))
        .andExpect(jsonPath("$.error.details[0].field").value("requirements"));

    mvc.perform(authed(get(POSTINGS + "/" + id), owner))
        .andExpect(jsonPath("$.status").value("DRAFT"))
        .andExpect(jsonPath("$.version").value(1));
    assertThat(events(id, "job.posting_published")).isZero();
  }

  // BR-JOB-14: archivar es automático; el dueño no lo puede pedir, ni saltarse el ciclo.
  @Test
  void aTransitionThatDoesNotExistIsA409InvalidTransition() throws Exception {
    UUID owner = recruiter();
    String id = createPublished(owner);

    mvc.perform(
            json(
                post(POSTINGS + "/" + id + "/transitions"),
                owner,
                TRANSITION.formatted("ARCHIVED", 3)))
        .andExpect(status().isConflict())
        .andExpect(jsonPath(ERROR_CODE).value("INVALID_TRANSITION"));
    mvc.perform(
            json(
                post(POSTINGS + "/" + id + "/transitions"),
                owner,
                TRANSITION.formatted("DRAFT", 3)))
        .andExpect(status().isConflict())
        .andExpect(jsonPath(ERROR_CODE).value("INVALID_TRANSITION"));
    mvc.perform(json(post(POSTINGS + "/" + id + "/transitions"), owner, "{\"version\":3}"))
        .andExpect(status().isBadRequest());
  }

  // Un `[null]` del cliente es un dato inválido (400 con la posición), no un error interno (500).
  @Test
  void aNullRequirementInTheBodyIsA400NotA500() throws Exception {
    UUID owner = recruiter();
    String id = createDraft(owner);

    mvc.perform(
            json(
                put(POSTINGS + "/" + id + "/requirements"),
                owner,
                "{\"requirements\":[null],\"version\":1}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath(ERROR_CODE).value("VALIDATION_FAILED"))
        .andExpect(jsonPath("$.error.details[0].field").value("requirements[0].type"));
  }

  @Test
  void aPublishedPostingCannotLoseItsLastSkill() throws Exception {
    UUID owner = recruiter();
    String id = createPublished(owner);

    mvc.perform(
            json(
                put(POSTINGS + "/" + id + "/requirements"),
                owner,
                "{\"requirements\":[],\"version\":3}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error.details[0].code").value("REQUIRED_TO_PUBLISH"));
  }

  // BR-JOB-10 y BR-JOB-04: ejes independientes, solo con la vacante publicada.
  @Test
  void pausingApplicationsAndReviewingWorkOnAPublishedPostingAndNotOnADraft() throws Exception {
    UUID owner = recruiter();
    String draft = createDraft(owner);
    String id = createPublished(owner);

    mvc.perform(
            json(
                put(POSTINGS + "/" + draft + "/accepting-applications"),
                owner,
                "{\"accepting\":false,\"version\":1}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath(ERROR_CODE).value("INVALID_STATE"));
    mvc.perform(
            json(
                put(POSTINGS + "/" + id + "/accepting-applications"),
                owner,
                "{\"accepting\":false,\"version\":3}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.acceptingApplications").value(false))
        .andExpect(jsonPath("$.status").value("PUBLISHED"));
    mvc.perform(
            json(
                put(POSTINGS + "/" + id + "/recruiting-stage"),
                owner,
                "{\"stage\":\"REVIEWING\",\"version\":4}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.recruitingStage").value("REVIEWING"))
        .andExpect(jsonPath("$.version").value(5));

    assertThat(events(id, "job.recruiting_stage_changed")).isOne();
  }

  @Test
  void editingAPublishedPostingEmitsUpdatedAndResendingTheSameDataDoesNot() throws Exception {
    UUID owner = recruiter();
    String id = createPublished(owner);

    mvc.perform(
            json(
                put(POSTINGS + "/" + id),
                owner,
                BODY.formatted(3).replace("Backend", "Backend Senior")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.title").value("Backend Senior"))
        .andExpect(jsonPath("$.version").value(4));
    mvc.perform(
            json(
                put(POSTINGS + "/" + id),
                owner,
                BODY.formatted(4).replace("Backend", "Backend Senior")))
        .andExpect(jsonPath("$.version").value(4));

    assertThat(events(id, "job.posting_updated")).isOne();
  }

  @Test
  void invalidDataReturnsEveryViolationTogether() throws Exception {
    UUID owner = recruiter();

    mvc.perform(
            json(
                post(POSTINGS),
                owner,
                "{\"title\":\" \",\"description\":\"d\",\"country\":\"ZZ\",\"salaryMin\":100,"
                    + "\"version\":0}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath(ERROR_CODE).value("VALIDATION_FAILED"))
        .andExpect(jsonPath("$.error.details.length()").value(4));
  }

  // ---- concurrencia ----

  @Test
  void twoSimultaneousClosuresHaveExactlyOneWinnerAndOneEvent() throws Exception {
    UUID owner = recruiter();
    String id = createPublished(owner);
    ExecutorService pool = Executors.newFixedThreadPool(2);
    CountDownLatch start = new CountDownLatch(1);
    List<Future<Integer>> results = new ArrayList<>();
    for (int i = 0; i < 2; i++) {
      Callable<Integer> call =
          () -> {
            start.await();
            return mvc.perform(
                    json(
                        post(POSTINGS + "/" + id + "/transitions"),
                        owner,
                        TRANSITION.formatted("CLOSED", 3)))
                .andReturn()
                .getResponse()
                .getStatus();
          };
      results.add(pool.submit(call));
    }
    start.countDown();
    List<Integer> statuses = new ArrayList<>();
    for (Future<Integer> result : results) {
      statuses.add(result.get());
    }
    pool.shutdown();

    assertThat(statuses).containsExactlyInAnyOrder(200, 409);
    assertThat(events(id, "job.posting_closed")).isOne();
  }

  @Test
  void aStaleVersionIsA409AndNothingChanges() throws Exception {
    UUID owner = recruiter();
    String id = createDraft(owner);

    mvc.perform(json(put(POSTINGS + "/" + id), owner, BODY.formatted(9).replace("Backend", "Otro")))
        .andExpect(status().isConflict())
        .andExpect(jsonPath(ERROR_CODE).value("STALE_STATE"));
    mvc.perform(json(put(POSTINGS + "/" + id), owner, BODY.replace(",\"version\":%d", "")))
        .andExpect(status().isBadRequest());
    mvc.perform(authed(get(POSTINGS + "/" + id), owner))
        .andExpect(jsonPath("$.title").value("Backend"));
  }

  // ---- quién puede qué ----

  // BR-JOB-01 y BR-ACCT-04.
  @Test
  void anAccountWithoutTheRecruiterCapabilityIsForbidden() throws Exception {
    UUID onlyCandidate = candidate();

    mvc.perform(json(post(POSTINGS), onlyCandidate, BODY.formatted(0)))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath(ERROR_CODE).value("FORBIDDEN_CAPABILITY"));
  }

  // BR-VIS-03: la vacante de otro reclutador no existe; ni leerla, ni editarla, ni cambiarle el
  // estado.
  @Test
  void anotherRecruitersPostingDoesNotExist() throws Exception {
    UUID owner = recruiter();
    UUID other = recruiter();
    String id = createPublished(owner);

    mvc.perform(authed(get(POSTINGS + "/" + id), other)).andExpect(status().isNotFound());
    mvc.perform(json(put(POSTINGS + "/" + id), other, BODY.formatted(3)))
        .andExpect(status().isNotFound());
    mvc.perform(json(put(POSTINGS + "/" + id + "/requirements"), other, JAVA.formatted(3)))
        .andExpect(status().isNotFound());
    mvc.perform(
            json(
                post(POSTINGS + "/" + id + "/transitions"),
                other,
                TRANSITION.formatted("CLOSED", 3)))
        .andExpect(status().isNotFound());
    mvc.perform(authed(get(POSTINGS + "/" + UUID.randomUUID()), owner))
        .andExpect(status().isNotFound());
    mvc.perform(authed(get(POSTINGS + "/no-es-un-uuid"), owner)).andExpect(status().isNotFound());
  }

  // BR-JOB-11: un candidato ve publicadas y cerradas, sin los campos de gestión ni al reclutador.
  @Test
  void aCandidateSeesPublishedPostingsWithoutManagementFieldsAndNeverDrafts() throws Exception {
    UUID owner = recruiter();
    UUID reader = candidate();
    String draft = createDraft(owner);
    String published = createPublished(owner);

    mvc.perform(authed(get(POSTINGS + "/" + draft), reader)).andExpect(status().isNotFound());
    MvcResult seen =
        mvc.perform(authed(get(POSTINGS + "/" + published), reader))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.title").value("Backend"))
            .andExpect(jsonPath("$.requirements[0].skillName").value("Java"))
            .andExpect(jsonPath("$.version").doesNotExist())
            .andExpect(jsonPath("$.recruitingStage").doesNotExist())
            .andExpect(jsonPath("$.createdAt").doesNotExist())
            .andReturn();
    assertThat(seen.getResponse().getContentAsString()).doesNotContain(owner.toString());
  }

  @Test
  void aRecruiterThatIsNotACandidateCannotReadSomeoneElsesPublishedPosting() throws Exception {
    UUID owner = recruiter();
    UUID otherRecruiter = recruiter();
    String published = createPublished(owner);

    mvc.perform(authed(get(POSTINGS + "/" + published), otherRecruiter))
        .andExpect(status().isNotFound());
  }

  @Test
  void aRequestWithoutATokenIsRejected() throws Exception {
    mvc.perform(get(POSTINGS + "/" + UUID.randomUUID())).andExpect(status().isUnauthorized());
    mvc.perform(post(POSTINGS).contentType(MediaType.APPLICATION_JSON).content(BODY.formatted(0)))
        .andExpect(status().isUnauthorized());
  }

  // ---- privacidad y lectura para matching ----

  // BR-DATA-01: la bandeja lleva identificadores y versión, nunca el contenido ni al reclutador.
  @Test
  void theOutboxPayloadsCarryNoContentAndNoRecruiter() throws Exception {
    UUID owner = recruiter();
    String id = createPublished(owner);

    List<String> payloads =
        jdbc.queryForList(
            "select payload::text from outbox_events where aggregate_id = ?::uuid",
            String.class,
            id);

    assertThat(payloads)
        .isNotEmpty()
        .allSatisfy(
            payload ->
                assertThat(payload)
                    .contains(id)
                    .doesNotContain("Backend", "Java", owner.toString(), "USD"));
  }

  @Test
  void matchingReadsTheContentThroughTheSignalsUseCaseAndNeverTheRecruiter() throws Exception {
    UUID owner = recruiter();
    String draft = createDraft(owner);
    String id = createPublished(owner);

    JobSnapshot snapshot =
        signals.execute(new GetJobSignalsQuery(UUID.fromString(id))).orElseThrow();

    assertThat(snapshot.status()).isEqualTo("PUBLISHED");
    assertThat(snapshot.salary().currency()).isEqualTo("USD");
    assertThat(snapshot.requirements())
        .singleElement()
        .satisfies(r -> assertThat(r.skillName()).isEqualTo("Java"));
    assertThat(snapshot.toString()).doesNotContain(owner.toString());
    assertThat(signals.execute(new GetJobSignalsQuery(UUID.fromString(draft)))).isEmpty();
  }

  // ---- confianza en el reclutador: la puerta de publicar (REQ-R-10, amenaza T-N1) ----

  /** Un borrador con una habilidad, listo para publicar: queda en la versión 2. */
  private String draftWithSkill(UUID owner) throws Exception {
    String id = createDraft(owner);
    mvc.perform(json(put(POSTINGS + "/" + id + "/requirements"), owner, JAVA.formatted(1)))
        .andExpect(status().isOk());
    return id;
  }

  private org.springframework.test.web.servlet.ResultActions publish(String id, UUID owner)
      throws Exception {
    return mvc.perform(
        json(
            post(POSTINGS + "/" + id + "/transitions"),
            owner,
            TRANSITION.formatted("PUBLISHED", 2)));
  }

  private void setVerification(UUID account, RecruiterVerification value) {
    verification.execute(new ChangeRecruiterVerificationCommand(new AccountId(account), value));
  }

  @Test
  void anUnverifiedRecruiterPublishesUpToItsLimitAndThenIsToldToVerify() throws Exception {
    UUID owner = recruiter();
    String first = draftWithSkill(owner);
    String second = draftWithSkill(owner);
    String third = draftWithSkill(owner);

    publish(first, owner).andExpect(status().isOk());
    publish(second, owner).andExpect(status().isOk());
    publish(third, owner)
        .andExpect(status().isForbidden())
        .andExpect(jsonPath(ERROR_CODE).value("RECRUITER_NOT_VERIFIED"));

    // La denegada no cambió nada ni emitió evento; el borrador sigue siendo suyo.
    mvc.perform(authed(get(POSTINGS + "/" + third), owner))
        .andExpect(jsonPath("$.status").value("DRAFT"))
        .andExpect(jsonPath("$.version").value(2));
    assertThat(events(third, "job.posting_published")).isZero();
  }

  // Crear y editar borradores sigue libre aunque el tope esté lleno: solo publicar cruza la puerta.
  @Test
  void draftsStayFreeEvenWhenTheLimitIsReached() throws Exception {
    UUID owner = recruiter();
    publish(draftWithSkill(owner), owner).andExpect(status().isOk());
    publish(draftWithSkill(owner), owner).andExpect(status().isOk());

    String extra = createDraft(owner);

    mvc.perform(
            json(put(POSTINGS + "/" + extra), owner, BODY.formatted(1).replace("Backend", "Otro")))
        .andExpect(status().isOk());
  }

  @Test
  void verifyingTheAccountRaisesTheLimitAndClosingAPostingFreesASlot() throws Exception {
    UUID owner = recruiter();
    String a = draftWithSkill(owner);
    String b = draftWithSkill(owner);
    String c = draftWithSkill(owner);
    publish(a, owner).andExpect(status().isOk());
    publish(b, owner).andExpect(status().isOk());
    publish(c, owner).andExpect(status().isForbidden());

    setVerification(owner, RecruiterVerification.VERIFIED);
    publish(c, owner).andExpect(status().isOk());

    // Verificada, el tope es 3: la cuarta es una cuota agotada, no una cuenta sin verificar.
    String d = draftWithSkill(owner);
    publish(d, owner)
        .andExpect(status().isTooManyRequests())
        .andExpect(jsonPath(ERROR_CODE).value("QUOTA_EXCEEDED"));

    mvc.perform(
            json(
                post(POSTINGS + "/" + a + "/transitions"),
                owner,
                TRANSITION.formatted("CLOSED", 3)))
        .andExpect(status().isOk());
    publish(d, owner).andExpect(status().isOk());
  }

  // Reabrir una cerrada ocupa cupo: cerrar y reabrir no es una forma de saltarse el tope.
  @Test
  void reopeningAClosedPostingGoesThroughTheSameLimit() throws Exception {
    UUID owner = recruiter();
    String a = draftWithSkill(owner);
    publish(a, owner).andExpect(status().isOk());
    mvc.perform(
            json(
                post(POSTINGS + "/" + a + "/transitions"),
                owner,
                TRANSITION.formatted("CLOSED", 3)))
        .andExpect(status().isOk());
    publish(draftWithSkill(owner), owner).andExpect(status().isOk());
    publish(draftWithSkill(owner), owner).andExpect(status().isOk());

    mvc.perform(
            json(
                post(POSTINGS + "/" + a + "/transitions"),
                owner,
                TRANSITION.formatted("PUBLISHED", 4)))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath(ERROR_CODE).value("RECRUITER_NOT_VERIFIED"));
  }

  @Test
  void aSuspendedRecruiterCannotPublishAndAReinstatedOneCan() throws Exception {
    UUID owner = recruiter();
    String draft = draftWithSkill(owner);
    setVerification(owner, RecruiterVerification.SUSPENDED);

    publish(draft, owner)
        .andExpect(status().isForbidden())
        .andExpect(jsonPath(ERROR_CODE).value("RECRUITER_SUSPENDED"));

    setVerification(owner, RecruiterVerification.VERIFIED);
    publish(draft, owner).andExpect(status().isOk());
  }

  // El tope se cuenta por cuenta: lo publicado por otro reclutador no ocupa el cupo propio.
  @Test
  void theLimitIsPerRecruiter() throws Exception {
    UUID busy = recruiter();
    UUID other = recruiter();
    publish(draftWithSkill(busy), busy).andExpect(status().isOk());
    publish(draftWithSkill(busy), busy).andExpect(status().isOk());

    publish(draftWithSkill(other), other).andExpect(status().isOk());
  }

  // Dos publicaciones simultáneas con un solo cupo libre: una gana y la otra recibe el tope. Sin el
  // candado por reclutador ambas contarían «una menos que el tope» y pasarían las dos. La ventana
  // de
  // carrera dura milisegundos, así que se repite en varias rondas con una cuenta nueva cada una:
  // una
  // sola ronda casi nunca la alcanza y la prueba pasaría aunque el candado no existiera.
  @Test
  void twoSimultaneousPublicationsWithOneSlotLeftHaveExactlyOneWinnerInEveryRound()
      throws Exception {
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      for (int round = 0; round < ROUNDS; round++) {
        UUID owner = recruiter();
        publish(draftWithSkill(owner), owner).andExpect(status().isOk());
        List<String> contenders = List.of(draftWithSkill(owner), draftWithSkill(owner));
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        for (String id : contenders) {
          Callable<Integer> call =
              () -> {
                start.await();
                return publish(id, owner).andReturn().getResponse().getStatus();
              };
          results.add(pool.submit(call));
        }
        start.countDown();
        List<Integer> statuses = new ArrayList<>();
        for (Future<Integer> result : results) {
          statuses.add(result.get());
        }

        assertThat(statuses).as("ronda %d".formatted(round)).containsExactlyInAnyOrder(200, 403);
        assertThat(
                jdbc.queryForObject(
                    "select count(*) from job_postings where recruiter_account_id = ? and status ="
                        + " 'PUBLISHED'",
                    Integer.class,
                    owner))
            .isEqualTo(2);
      }
    } finally {
      pool.shutdown();
    }
  }

  @Test
  void aNewRecruiterStartsUnverifiedAndOnlyARecruiterHasAVerification() throws Exception {
    UUID owner = recruiter();
    UUID onlyCandidate = candidate();

    assertThat(
            jdbc.queryForObject(
                "select recruiter_verification from accounts where id = ?", String.class, owner))
        .isEqualTo("UNVERIFIED");
    org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> setVerification(onlyCandidate, RecruiterVerification.VERIFIED))
        .isInstanceOf(com.usac.jobseeker.common.domain.error.NotFoundException.class);
  }
}
