package com.usac.jobseeker.app.infrastructure.security.webhook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.usac.jobseeker.app.InfrastructureTestConfiguration;
import com.usac.jobseeker.app.infrastructure.outbox.OutboxPoller;
import com.usac.jobseeker.app.infrastructure.outbox.WithoutOutboxScheduler;
import com.usac.jobseeker.app.infrastructure.security.liveness.LivenessStore;
import com.usac.jobseeker.app.infrastructure.security.liveness.VaultingAuthorizedClientRepository;
import com.usac.jobseeker.common.application.outbox.DomainEventPublisherPort;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.data.redis.RedisIndexedSessionRepository;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * La superficie C completa (ADR-0020): la cadena de seguridad real, Postgres y Redis reales y
 * entregas firmadas como las firma Clavaris. Comprueba de punta a punta lo que importa: que una
 * entrega auténtica cierra las sesiones, que una repetida no se aplica dos veces y que ninguna
 * forma de entrega falsa o dañada tiene efecto alguno.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(InfrastructureTestConfiguration.class)
// El sondeo se dispara a mano para que cada prueba controle cuándo ocurre.
@WithoutOutboxScheduler
class ClavarisWebhookIntegrationTest {

  private static final String URL = "/api/v1/webhooks/clavaris";
  private static final String SECRET = "whsec-de-prueba-vigente";
  private static final String PREVIOUS = "whsec-de-prueba-anterior";

  @DynamicPropertySource
  static void webhook(DynamicPropertyRegistry registry) {
    registry.add("jobseeker.auth.webhook.secret", () -> SECRET);
    registry.add("jobseeker.auth.webhook.previous-secret", () -> PREVIOUS);
    registry.add("jobseeker.auth.webhook.max-body-bytes", () -> "2048");
  }

  @Autowired private MockMvc mvc;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private OutboxPoller poller;
  @Autowired private ApplicationContext context;
  @Autowired private RedisIndexedSessionRepository sessions;
  @Autowired private LivenessStore liveness;
  @MockitoSpyBean private DomainEventPublisherPort publisher;

  private UUID account;

  @BeforeEach
  void newAccount() {
    reset(publisher);
    account = UUID.randomUUID();
    jdbc.update(
        "insert into accounts (id, is_candidate_enabled, is_recruiter_enabled)"
            + " values (?, true, false)",
        account);
  }

  // ---- ayudas ----

  private static String body(UUID accountId, Instant at) {
    return "{\"accountId\":{\"value\":\"%s\"},\"organizationId\":{\"value\":\"%s\"},\"occurredAt\":\"%s\"}"
        .formatted(accountId, UUID.randomUUID(), at);
  }

  private MockHttpServletRequestBuilder delivery(
      String type, UUID eventId, String json, String signature) {
    MockHttpServletRequestBuilder request =
        post(URL)
            .contentType("application/json")
            .content(json.getBytes(StandardCharsets.UTF_8))
            .header("Clavaris-Event-Type", type)
            .header("Clavaris-Event-Id", eventId.toString())
            .header("Clavaris-Delivery-Id", UUID.randomUUID().toString());
    return signature == null ? request : request.header("Clavaris-Signature", signature);
  }

  private MvcResult send(String type, UUID eventId, Instant occurredAt, String... secrets)
      throws Exception {
    String json = body(account, occurredAt);
    String signature =
        WebhookSigner.header(
            Instant.now(), json.getBytes(StandardCharsets.UTF_8), List.of(secrets));
    return mvc.perform(delivery(type, eventId, json, signature)).andReturn();
  }

  private MvcResult send(String type, UUID eventId, Instant occurredAt) throws Exception {
    return send(type, eventId, occurredAt, SECRET);
  }

  private String identityStatus() {
    return jdbc.queryForObject(
        "select identity_status from accounts where id = ?", String.class, account);
  }

  private long outboxRows() {
    return jdbc.queryForObject(
        "select count(*) from outbox_events where aggregate_id = ?", Long.class, account);
  }

  private long processedRows(UUID eventId) {
    return jdbc.queryForObject(
        "select count(*) from processed_events where event_id = ?", Long.class, eventId);
  }

  private String openSession() {
    var session = sessions.createSession();
    session.setAttribute(
        FindByIndexNameSessionRepository.PRINCIPAL_NAME_INDEX_NAME, account.toString());
    sessions.save(session);
    return session.getId();
  }

  private static void assertEnvelope(MvcResult result, int status, String code) throws Exception {
    assertThat(result.getResponse().getStatus()).isEqualTo(status);
    assertThat(result.getResponse().getContentAsString())
        .contains("\"code\":\"" + code + "\"", "\"traceId\"", "\"timestamp\"");
  }

  @Test
  void theRealOutboxSchedulerIsReplacedByAMockSoOnlyTheTestPollsTheOutbox() {
    // Guarda de @WithoutOutboxScheduler: si dejara de aplicarse, estas pruebas volverían a ser
    // intermitentes sin que nada lo avisara.
    assertThat(Mockito.mockingDetails(context.getBean("outboxScheduler")).isMock()).isTrue();
  }

  // ---- una entrega auténtica ----

  @Test
  void anAuthenticSuspensionIsAcknowledgedAppliedAndClosesTheSessionsThroughTheOutbox()
      throws Exception {
    String first = openSession();
    String second = openSession();
    UUID eventId = UUID.randomUUID();

    MvcResult result = send("account.suspended", eventId, Instant.now());

    assertThat(result.getResponse().getStatus()).isEqualTo(204);
    assertThat(result.getResponse().getHeader("Set-Cookie")).as("sin sesión propia").isNull();
    assertThat(identityStatus()).isEqualTo("SUSPENDED");
    assertThat(processedRows(eventId)).isOne();
    assertThat(outboxRows()).isOne();
    assertThat(sessions.findById(first)).as("aún no: va por la bandeja").isNotNull();

    poller.pollOnce();

    assertThat(sessions.findById(first)).isNull();
    assertThat(sessions.findById(second)).isNull();
    assertThat(
            jdbc.queryForObject(
                "select status from outbox_events where aggregate_id = ?", String.class, account))
        .isEqualTo("PROCESSED");
  }

  @Test
  void anAuthenticBanClosesTheSessionsThroughTheOutboxLikeASuspension() throws Exception {
    String session = openSession();

    MvcResult result = send("account.banned", UUID.randomUUID(), Instant.now());

    assertThat(result.getResponse().getStatus()).isEqualTo(204);
    assertThat(identityStatus()).isEqualTo("SUSPENDED");
    assertThat(outboxRows()).isOne();
    poller.pollOnce();
    assertThat(sessions.findById(session)).isNull();
  }

  @Test
  void anUnbanRestoresTheStatusAndLeavesNothingToClose() throws Exception {
    Instant now = Instant.now();
    send("account.banned", UUID.randomUUID(), now);

    send("account.unbanned", UUID.randomUUID(), now.plusSeconds(5));

    assertThat(identityStatus()).isEqualTo("ACTIVE");
    assertThat(outboxRows()).as("solo el baneo pidió cerrar sesiones").isOne();
  }

  @Test
  void closingTheSessionsAlsoDeletesTheirLivenessRecordsAndLeavesOtherAccountsAlone()
      throws Exception {
    String link = liveness.begin("refresh-token-de-prueba", Instant.now());
    var mine = sessions.createSession();
    mine.setAttribute(
        FindByIndexNameSessionRepository.PRINCIPAL_NAME_INDEX_NAME, account.toString());
    mine.setAttribute(VaultingAuthorizedClientRepository.LINK_ATTRIBUTE, link);
    sessions.save(mine);
    String otherLink = liveness.begin("refresh-token-de-otra-cuenta", Instant.now());
    var other = sessions.createSession();
    other.setAttribute(
        FindByIndexNameSessionRepository.PRINCIPAL_NAME_INDEX_NAME, UUID.randomUUID().toString());
    other.setAttribute(VaultingAuthorizedClientRepository.LINK_ATTRIBUTE, otherLink);
    sessions.save(other);
    assertThat(liveness.find(link)).as("antes del cierre existe").isPresent();

    send("account.suspended", UUID.randomUUID(), Instant.now());
    poller.pollOnce();

    assertThat(sessions.findById(mine.getId())).isNull();
    assertThat(liveness.find(link)).as("sin registro huérfano").isEmpty();
    assertThat(sessions.findById(other.getId())).isNotNull();
    assertThat(liveness.find(otherLink)).as("el de otra cuenta sigue").isPresent();
  }

  @Test
  void aSessionOfAnotherAccountIsNotTouched() throws Exception {
    UUID other = UUID.randomUUID();
    var session = sessions.createSession();
    session.setAttribute(
        FindByIndexNameSessionRepository.PRINCIPAL_NAME_INDEX_NAME, other.toString());
    sessions.save(session);

    send("account.suspended", UUID.randomUUID(), Instant.now());
    poller.pollOnce();

    assertThat(sessions.findById(session.getId())).isNotNull();
  }

  @Test
  void aDeliverySignedWithThePreviousSecretIsAcceptedDuringARotation() throws Exception {
    assertThat(
            send("account.suspended", UUID.randomUUID(), Instant.now(), PREVIOUS)
                .getResponse()
                .getStatus())
        .isEqualTo(204);
    assertThat(identityStatus()).isEqualTo("SUSPENDED");
  }

  @Test
  void aDeliverySignedWithBothSecretsLikeClavarisDoesDuringARotation() throws Exception {
    assertThat(
            send("account.suspended", UUID.randomUUID(), Instant.now(), SECRET, PREVIOUS)
                .getResponse()
                .getStatus())
        .isEqualTo(204);
  }

  // ---- repetición, orden y concurrencia ----

  @Test
  void theSameDeliveryTwiceIsAcknowledgedBothTimesButAppliedOnce() throws Exception {
    UUID eventId = UUID.randomUUID();
    Instant at = Instant.now();

    assertThat(send("account.suspended", eventId, at).getResponse().getStatus()).isEqualTo(204);
    assertThat(send("account.suspended", eventId, at).getResponse().getStatus()).isEqualTo(204);

    assertThat(outboxRows()).isOne();
    assertThat(processedRows(eventId)).isOne();
  }

  @Test
  void sixteenSimultaneousCopiesOfTheSameDeliveryApplyExactlyOnce() throws Exception {
    UUID eventId = UUID.randomUUID();
    Instant at = Instant.now();
    String json = body(account, at);
    String signature =
        WebhookSigner.header(Instant.now(), json.getBytes(StandardCharsets.UTF_8), List.of(SECRET));
    CountDownLatch together = new CountDownLatch(1);
    ExecutorService pool = Executors.newFixedThreadPool(16);
    try {
      List<Future<Integer>> results = new ArrayList<>();
      for (int i = 0; i < 16; i++) {
        results.add(
            pool.submit(
                () -> {
                  together.await();
                  return mvc.perform(delivery("account.suspended", eventId, json, signature))
                      .andReturn()
                      .getResponse()
                      .getStatus();
                }));
      }
      together.countDown();
      for (Future<Integer> result : results) {
        assertThat(result.get(60, TimeUnit.SECONDS)).isEqualTo(204);
      }
    } finally {
      pool.shutdownNow();
    }

    assertThat(outboxRows()).as("un solo efecto").isOne();
    assertThat(processedRows(eventId)).isOne();
  }

  @Test
  void aSuspensionArrivingAfterANewerReactivationDoesNotRevertItButStillClosesSessions()
      throws Exception {
    Instant now = Instant.now();
    send("account.reactivated", UUID.randomUUID(), now);

    send("account.suspended", UUID.randomUUID(), now.minusSeconds(60));

    assertThat(identityStatus()).isEqualTo("ACTIVE");
    assertThat(outboxRows()).as("cerrar de más es la dirección segura").isOne();
  }

  @Test
  void deletionIsTerminalWhateverArrivesAfterIt() throws Exception {
    Instant now = Instant.now();
    send("account.deleted", UUID.randomUUID(), now.minusSeconds(3600));

    send("account.reactivated", UUID.randomUUID(), now);

    assertThat(identityStatus()).isEqualTo("DELETED");
  }

  @Test
  void aDeletedIdentityIsRejectedByTheActorResolutionOfTheApi() throws Exception {
    send("account.deleted", UUID.randomUUID(), Instant.now());

    assertThat(identityStatus()).isEqualTo("DELETED");
    assertThat(
            jdbc.queryForObject(
                "select count(*) from accounts where id = ? and identity_status = 'DELETED'",
                Long.class,
                account))
        .isOne();
  }

  // ---- atomicidad: un fallo no deja rastro y el reintento aplica una vez ----

  @Test
  void ifTheOutboxWriteFailsNothingIsLeftBehindAndClavarisGetsA503ToRetry() throws Exception {
    UUID eventId = UUID.randomUUID();
    Instant at = Instant.now();
    doThrow(new IllegalStateException("bandeja caída"))
        .when(publisher)
        .publish(org.mockito.ArgumentMatchers.any());

    MvcResult failed = send("account.suspended", eventId, at);

    assertEnvelope(failed, 503, "WEBHOOK_UNAVAILABLE");
    assertThat(processedRows(eventId)).as("no se marcó como procesado").isZero();
    assertThat(identityStatus()).as("el efecto se revirtió").isEqualTo("ACTIVE");
    assertThat(outboxRows()).isZero();

    reset(publisher);
    assertThat(send("account.suspended", eventId, at).getResponse().getStatus()).isEqualTo(204);
    assertThat(identityStatus()).isEqualTo("SUSPENDED");
    assertThat(outboxRows()).isOne();
  }

  // ---- lo que no tiene efecto ----

  @Test
  void anEventTypeWithoutEffectIsAcknowledgedAndChangesNothing() throws Exception {
    UUID eventId = UUID.randomUUID();

    assertThat(send("account.profile_updated", eventId, Instant.now()).getResponse().getStatus())
        .isEqualTo(204);

    assertThat(identityStatus()).isEqualTo("ACTIVE");
    assertThat(outboxRows()).isZero();
    assertThat(processedRows(eventId)).isZero();
  }

  @Test
  void anAccountThatNeverLoggedInIsAcknowledgedWithNothingToClose() throws Exception {
    UUID stranger = UUID.randomUUID();
    String json = body(stranger, Instant.now());
    String signature =
        WebhookSigner.header(Instant.now(), json.getBytes(StandardCharsets.UTF_8), List.of(SECRET));

    MvcResult result =
        mvc.perform(delivery("account.deleted", UUID.randomUUID(), json, signature)).andReturn();

    assertThat(result.getResponse().getStatus()).isEqualTo(204);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from outbox_events where aggregate_id = ?", Long.class, stranger))
        .isZero();
  }

  // ---- entregas falsas o dañadas: ninguna tiene efecto ----

  private void assertNoEffect(UUID eventId) {
    assertThat(identityStatus()).isEqualTo("ACTIVE");
    assertThat(outboxRows()).isZero();
    assertThat(processedRows(eventId)).isZero();
  }

  @Test
  void aMissingSignatureIsRejectedWith401AndTheStandardEnvelope() throws Exception {
    UUID eventId = UUID.randomUUID();

    MvcResult result =
        mvc.perform(delivery("account.suspended", eventId, body(account, Instant.now()), null))
            .andReturn();

    assertEnvelope(result, 401, "WEBHOOK_SIGNATURE_INVALID");
    assertNoEffect(eventId);
  }

  @Test
  void aWrongSecretIsRejectedWith401() throws Exception {
    UUID eventId = UUID.randomUUID();

    assertEnvelope(
        send("account.suspended", eventId, Instant.now(), "secreto-ajeno"),
        401,
        "WEBHOOK_SIGNATURE_INVALID");
    assertNoEffect(eventId);
  }

  @Test
  void aBodyChangedAfterSigningIsRejected() throws Exception {
    UUID eventId = UUID.randomUUID();
    String signed = body(account, Instant.now());
    String signature =
        WebhookSigner.header(
            Instant.now(), signed.getBytes(StandardCharsets.UTF_8), List.of(SECRET));
    String forged = signed.replace(account.toString(), UUID.randomUUID().toString());

    assertEnvelope(
        mvc.perform(delivery("account.suspended", eventId, forged, signature)).andReturn(),
        401,
        "WEBHOOK_SIGNATURE_INVALID");
    assertNoEffect(eventId);
  }

  @Test
  void aValidSignatureReplayedOutsideTheToleranceIsRejected() throws Exception {
    UUID eventId = UUID.randomUUID();
    String json = body(account, Instant.now());
    String old =
        WebhookSigner.header(
            Instant.now().minusSeconds(3600),
            json.getBytes(StandardCharsets.UTF_8),
            List.of(SECRET));

    assertEnvelope(
        mvc.perform(delivery("account.suspended", eventId, json, old)).andReturn(),
        401,
        "WEBHOOK_SIGNATURE_INVALID");
    assertNoEffect(eventId);
  }

  @Test
  void aMalformedSignatureHeaderIsRejectedWith401() throws Exception {
    UUID eventId = UUID.randomUUID();

    assertEnvelope(
        mvc.perform(delivery("account.suspended", eventId, body(account, Instant.now()), "basura"))
            .andReturn(),
        401,
        "WEBHOOK_SIGNATURE_INVALID");
    assertNoEffect(eventId);
  }

  @Test
  void aBearerTokenIsNotAnAlternativeWayInSoOnlyTheSignatureAuthenticates() throws Exception {
    UUID eventId = UUID.randomUUID();

    MvcResult result =
        mvc.perform(
                delivery("account.suspended", eventId, body(account, Instant.now()), null)
                    .header("Authorization", "Bearer cualquier-cosa"))
            .andReturn();

    assertEnvelope(result, 401, "WEBHOOK_SIGNATURE_INVALID");
    assertNoEffect(eventId);
  }

  @Test
  void aBodyOverTheLimitIsRejectedWith413BeforeBeingVerified() throws Exception {
    UUID eventId = UUID.randomUUID();
    String huge = "{\"padding\":\"" + "x".repeat(4096) + "\"}";
    String signature =
        WebhookSigner.header(Instant.now(), huge.getBytes(StandardCharsets.UTF_8), List.of(SECRET));

    assertEnvelope(
        mvc.perform(delivery("account.suspended", eventId, huge, signature)).andReturn(),
        413,
        "PAYLOAD_TOO_LARGE");
    assertNoEffect(eventId);
  }

  @Test
  void aSignedBodyWithoutTheExpectedShapeIsRejectedWith400AndLeavesNoTrace() throws Exception {
    UUID eventId = UUID.randomUUID();
    String json = "{\"algo\":\"distinto\"}";
    String signature =
        WebhookSigner.header(Instant.now(), json.getBytes(StandardCharsets.UTF_8), List.of(SECRET));

    assertEnvelope(
        mvc.perform(delivery("account.suspended", eventId, json, signature)).andReturn(),
        400,
        "WEBHOOK_PAYLOAD_INVALID");
    assertNoEffect(eventId);
  }

  @Test
  void aSignedDeliveryWithoutItsEventIdHeaderIsRejectedWith400() throws Exception {
    String json = body(account, Instant.now());
    String signature =
        WebhookSigner.header(Instant.now(), json.getBytes(StandardCharsets.UTF_8), List.of(SECRET));

    MvcResult result =
        mvc.perform(
                post(URL)
                    .contentType("application/json")
                    .content(json)
                    .header("Clavaris-Event-Type", "account.suspended")
                    .header("Clavaris-Signature", signature))
            .andReturn();

    assertEnvelope(result, 400, "WEBHOOK_PAYLOAD_INVALID");
  }

  @Test
  void onlyPostIsAccepted() throws Exception {
    MvcResult result = mvc.perform(get(URL)).andReturn();

    assertEnvelope(result, 405, "METHOD_NOT_ALLOWED");
    assertThat(result.getResponse().getHeader("Allow")).isEqualTo("POST");
  }

  @Test
  void theReceiverNeverEchoesPersonalDataFromTheBodyInItsResponses() throws Exception {
    String json =
        "{\"accountId\":{\"value\":\"%s\"},\"email\":\"persona@ejemplo.com\",\"occurredAt\":\"%s\"}"
            .formatted(account, Instant.now());
    MvcResult rejected =
        mvc.perform(delivery("account.deleted", UUID.randomUUID(), json, "t=1,v1=00")).andReturn();

    assertThat(rejected.getResponse().getContentAsString()).doesNotContain("persona@ejemplo.com");
  }
}
