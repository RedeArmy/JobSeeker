package com.usac.jobseeker.app.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.usac.jobseeker.app.InfrastructureTestConfiguration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * El canal de verificación de reclutadores de punta a punta (BR-ACCT-11, TD-032): la cadena real de
 * la API, la lista de operadores de la configuración, la auditoría en Postgres y, al final, la
 * unión con la puerta de publicar (TD-030): verificar a un reclutador es lo que le sube el tope.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(InfrastructureTestConfiguration.class)
class RecruiterReviewEndpointIntegrationTest {

  private static final String ADMIN = "/api/v1/admin/recruiters/%s/verification";
  private static final String JOBS = "/api/v1/job/postings";
  private static final String ERROR_CODE = "$.error.code";
  private static final UUID OPERATOR = UUID.randomUUID();
  private static final StubIssuer ISSUER = startIssuer();
  private static final TestTokens KEYS = TestTokens.withKey("kid-review");

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
    registry.add("jobseeker.auth.operators.account-ids", OPERATOR::toString);
  }

  @AfterAll
  static void stop() {
    ISSUER.close();
  }

  @Autowired private MockMvc mvc;
  @Autowired private JdbcTemplate jdbc;

  @BeforeEach
  void prepare() {
    ISSUER.jwks = KEYS.jwks();
    ISSUER.jwksStatus = 200;
    // El operador es una cuenta local como cualquier otra: la cadena exige que exista.
    jdbc.update(
        "insert into accounts (id, is_candidate_enabled) values (?, true) on conflict do nothing",
        OPERATOR);
  }

  private String tokenOf(UUID account) {
    return KEYS.token(ISSUER.issuerUri(), Instant.now()).subject(account.toString()).build();
  }

  private MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder request, UUID account) {
    return request.header("Authorization", "Bearer " + tokenOf(account));
  }

  private MockHttpServletRequestBuilder json(
      MockHttpServletRequestBuilder request, UUID account, String body) {
    return as(request, account).contentType(MediaType.APPLICATION_JSON).content(body);
  }

  private UUID recruiter() {
    UUID id = UUID.randomUUID();
    jdbc.update("insert into accounts (id, is_recruiter_enabled) values (?, true)", id);
    return id;
  }

  private ResultActions review(UUID caller, UUID target, String body) throws Exception {
    return mvc.perform(json(put(ADMIN.formatted(target)), caller, body));
  }

  private String verification(UUID account) {
    return jdbc.queryForObject(
        "select recruiter_verification from accounts where id = ?", String.class, account);
  }

  private int auditRows(UUID target) {
    return jdbc.queryForObject(
        "select count(*) from audit_log where target_id = ?", Integer.class, target);
  }

  // ---- la decisión del operador ----

  @Test
  void anOperatorVerifiesARecruiterAndTheChangeIsAuditedWithoutPersonalData() throws Exception {
    UUID target = recruiter();

    review(OPERATOR, target, "{\"status\":\"VERIFIED\"}")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("VERIFIED"))
        .andExpect(jsonPath("$.changed").value(true));

    assertThat(verification(target)).isEqualTo("VERIFIED");
    Map<String, Object> row =
        jdbc.queryForMap(
            "select actor_account_id, action, target_type, details::text as details, trace_id"
                + " from audit_log where target_id = ?",
            target);
    assertThat(row)
        .containsEntry("actor_account_id", OPERATOR)
        .containsEntry("action", "RECRUITER_VERIFICATION_CHANGED")
        .containsEntry("target_type", "ACCOUNT")
        .doesNotContainEntry("trace_id", null);
    assertThat(row.get("details").toString())
        .contains("VERIFIED")
        .doesNotContain(target.toString())
        .doesNotContain(OPERATOR.toString());
  }

  // Repetir no cambia nada ni deja un rastro de algo que no ocurrió.
  @Test
  void repeatingTheSameDecisionIsIdempotentAndAuditedOnce() throws Exception {
    UUID target = recruiter();
    review(OPERATOR, target, "{\"status\":\"VERIFIED\"}").andExpect(status().isOk());

    review(OPERATOR, target, "{\"status\":\"VERIFIED\"}")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.changed").value(false));

    assertThat(auditRows(target)).isOne();
  }

  @Test
  void suspendingAndReinstatingEachLeaveTheirOwnTrail() throws Exception {
    UUID target = recruiter();
    review(OPERATOR, target, "{\"status\":\"VERIFIED\"}").andExpect(status().isOk());

    review(OPERATOR, target, "{\"status\":\"SUSPENDED\"}").andExpect(status().isOk());
    review(OPERATOR, target, "{\"status\":\"UNVERIFIED\"}").andExpect(status().isOk());

    assertThat(verification(target)).isEqualTo("UNVERIFIED");
    assertThat(
            jdbc.queryForList(
                "select details->>'to' from audit_log where target_id = ? order by at",
                String.class,
                target))
        .containsExactly("VERIFIED", "SUSPENDED", "UNVERIFIED");
  }

  // ---- quién puede ----

  // El canal no existe para quien no es operador: 404, sin cambiar ni auditar nada.
  @Test
  void aNonOperatorGetsNotFoundAndNothingChanges() throws Exception {
    UUID target = recruiter();
    UUID stranger = recruiter();

    review(stranger, target, "{\"status\":\"VERIFIED\"}").andExpect(status().isNotFound());

    assertThat(verification(target)).isEqualTo("UNVERIFIED");
    assertThat(auditRows(target)).isZero();
  }

  // Un reclutador no puede verificarse a sí mismo para saltarse el tope.
  @Test
  void aRecruiterCannotVerifyItselfEvenIfNotAnOperator() throws Exception {
    UUID me = recruiter();

    review(me, me, "{\"status\":\"VERIFIED\"}").andExpect(status().isNotFound());

    assertThat(verification(me)).isEqualTo("UNVERIFIED");
  }

  @Test
  void anOperatorCannotDecideAboutItself() throws Exception {
    jdbc.update("update accounts set is_recruiter_enabled = true where id = ?", OPERATOR);
    try {
      review(OPERATOR, OPERATOR, "{\"status\":\"VERIFIED\"}")
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath(ERROR_CODE).value("VALIDATION_FAILED"))
          .andExpect(jsonPath("$.error.details[0].code").value("SELF_REVIEW_FORBIDDEN"));
      assertThat(verification(OPERATOR)).isEqualTo("UNVERIFIED");
    } finally {
      jdbc.update("update accounts set is_recruiter_enabled = false where id = ?", OPERATOR);
    }
  }

  @Test
  void aRequestWithoutATokenIsRejected() throws Exception {
    mvc.perform(
            put(ADMIN.formatted(UUID.randomUUID()))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"VERIFIED\"}"))
        .andExpect(status().isUnauthorized());
  }

  // ---- la entrada ----

  @Test
  void anAccountThatIsNotARecruiterOrDoesNotExistIsNotFound() throws Exception {
    UUID candidateOnly = UUID.randomUUID();
    jdbc.update("insert into accounts (id, is_candidate_enabled) values (?, true)", candidateOnly);

    review(OPERATOR, candidateOnly, "{\"status\":\"VERIFIED\"}").andExpect(status().isNotFound());
    review(OPERATOR, UUID.randomUUID(), "{\"status\":\"VERIFIED\"}")
        .andExpect(status().isNotFound());
    mvc.perform(json(put(ADMIN.formatted("no-es-uuid")), OPERATOR, "{\"status\":\"VERIFIED\"}"))
        .andExpect(status().isNotFound());

    assertThat(auditRows(candidateOnly)).isZero();
  }

  @Test
  void aMissingOrUnknownStatusIsA400() throws Exception {
    UUID target = recruiter();

    review(OPERATOR, target, "{}")
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath(ERROR_CODE).value("VALIDATION_FAILED"));
    review(OPERATOR, target, "{\"status\":\"TRUSTED\"}").andExpect(status().isBadRequest());

    assertThat(verification(target)).isEqualTo("UNVERIFIED");
  }

  // ---- TD-030 y TD-032 juntos ----

  private String draftWithSkill(UUID owner) throws Exception {
    var created =
        mvc.perform(
                json(
                    post(JOBS),
                    owner,
                    "{\"title\":\"Backend\",\"description\":\"Servicios\",\"version\":0}"))
            .andExpect(status().isCreated())
            .andReturn();
    String id = JsonPath.read(created.getResponse().getContentAsString(), "$.id");
    mvc.perform(
            json(
                put(JOBS + "/" + id + "/requirements"),
                owner,
                "{\"requirements\":[{\"type\":\"SKILL\",\"skillName\":\"Java\"}],\"version\":1}"))
        .andExpect(status().isOk());
    return id;
  }

  private ResultActions publish(String id, UUID owner) throws Exception {
    return mvc.perform(
        json(
            post(JOBS + "/" + id + "/transitions"), owner, "{\"to\":\"PUBLISHED\",\"version\":2}"));
  }

  // Sin verificar publica hasta 2; al verificarlo, la tercera pasa: el canal es lo que sube el
  // tope.
  @Test
  void verifyingARecruiterRaisesItsPublishingLimit() throws Exception {
    UUID owner = recruiter();
    publish(draftWithSkill(owner), owner).andExpect(status().isOk());
    publish(draftWithSkill(owner), owner).andExpect(status().isOk());
    String third = draftWithSkill(owner);
    publish(third, owner)
        .andExpect(status().isForbidden())
        .andExpect(jsonPath(ERROR_CODE).value("RECRUITER_NOT_VERIFIED"));

    review(OPERATOR, owner, "{\"status\":\"VERIFIED\"}").andExpect(status().isOk());

    publish(third, owner).andExpect(status().isOk());
  }

  @Test
  void suspendingAVerifiedRecruiterStopsItFromPublishing() throws Exception {
    UUID owner = recruiter();
    review(OPERATOR, owner, "{\"status\":\"VERIFIED\"}").andExpect(status().isOk());
    String draft = draftWithSkill(owner);

    review(OPERATOR, owner, "{\"status\":\"SUSPENDED\"}").andExpect(status().isOk());

    publish(draft, owner)
        .andExpect(status().isForbidden())
        .andExpect(jsonPath(ERROR_CODE).value("RECRUITER_SUSPENDED"));
  }

  @Test
  void theChainSeesTheNewStateOnTheNextRequestAndNotAStaleOne() throws Exception {
    UUID owner = recruiter();
    List<String> seen =
        List.of(
            verificationAfter(owner, "VERIFIED"),
            verificationAfter(owner, "SUSPENDED"),
            verificationAfter(owner, "UNVERIFIED"));

    assertThat(seen).containsExactly("VERIFIED", "SUSPENDED", "UNVERIFIED");
  }

  private String verificationAfter(UUID owner, String value) throws Exception {
    review(OPERATOR, owner, "{\"status\":\"" + value + "\"}").andExpect(status().isOk());
    return verification(owner);
  }
}
