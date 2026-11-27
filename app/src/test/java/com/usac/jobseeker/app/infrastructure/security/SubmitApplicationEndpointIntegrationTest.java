package com.usac.jobseeker.app.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Postular de punta a punta (sub-paso 2 del TDD de `ats`): la cadena real de la API con tokens
 * firmados, Postgres y Redis reales, y los cinco módulos de verdad. Comprueba lo que los dobles no
 * pueden: la puerta real de `job`, BR-CAND-01 de `cv`, la copia en `matching` (TD-040), el evento
 * en la bandeja, la idempotencia sobre la tabla real y el índice de BR-TRACK-01.
 */
class SubmitApplicationEndpointIntegrationTest extends SharedIssuerIntegrationTest {

  private static final String POSTINGS = "/api/v1/job/postings";
  private static final String POSTING =
      "{\"title\":\"Backend\",\"description\":\"Servicios en Java\",\"version\":0}";
  private static final String JAVA =
      "{\"requirements\":[{\"type\":\"SKILL\",\"skillName\":\"Java\",\"minLevel\":3,"
          + "\"mandatory\":true}],\"version\":1}";
  private static final String TRANSITION = "{\"to\":\"%s\",\"version\":%d}";
  private static final String ERROR_CODE = "$.error.code";
  private static final String SECRET_NAME = "Ana Ejemplo";
  private static final String SECRET_EMAIL = "ana.ejemplo@example.test";

  @Autowired private MockMvc mvc;
  @Autowired private JdbcTemplate jdbc;

  private MockHttpServletRequestBuilder authed(
      MockHttpServletRequestBuilder request, UUID account) {
    String token =
        KEYS.token(ISSUER.issuerUri(), Instant.now()).subject(account.toString()).build();
    return request.header("Authorization", "Bearer " + token);
  }

  private MockHttpServletRequestBuilder json(
      MockHttpServletRequestBuilder request, UUID account, String body) {
    return authed(request, account).contentType(MediaType.APPLICATION_JSON).content(body);
  }

  private UUID account(boolean candidate, boolean recruiter) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "insert into accounts (id, is_candidate_enabled, is_recruiter_enabled) values (?, ?, ?)",
        id,
        candidate,
        recruiter);
    return id;
  }

  /** Una vacante publicada del reclutador; termina en la versión 3. */
  private String published(UUID owner) throws Exception {
    MvcResult created =
        mvc.perform(json(post(POSTINGS), owner, POSTING))
            .andExpect(status().isCreated())
            .andReturn();
    String id = JsonPath.read(created.getResponse().getContentAsString(), "$.id");
    mvc.perform(json(put(POSTINGS + "/" + id + "/requirements"), owner, JAVA))
        .andExpect(status().isOk());
    transition(owner, id, "PUBLISHED", 2);
    return id;
  }

  private void transition(UUID owner, String id, String to, long version) throws Exception {
    mvc.perform(
            json(
                post(POSTINGS + "/" + id + "/transitions"),
                owner,
                TRANSITION.formatted(to, version)))
        .andExpect(status().isOk());
  }

  /** Un candidato con nombre y contacto; con CV si `withCv`. */
  private UUID candidate(boolean withCv) throws Exception {
    UUID id = account(true, false);
    mvc.perform(
            json(
                put("/api/v1/me/professional-profile"),
                id,
                "{\"fullName\":\"" + SECRET_NAME + "\",\"version\":0}"))
        .andExpect(status().isCreated());
    mvc.perform(
            json(
                put("/api/v1/me/candidate-profile"),
                id,
                "{\"contactEmail\":\"" + SECRET_EMAIL + "\",\"version\":0}"))
        .andExpect(status().isCreated());
    if (withCv) {
      jdbc.update(
          "insert into candidate_cv_files (id, candidate_profile_id, object_key, display_name,"
              + " file_type, size_bytes, sha256, is_current, uploaded_at)"
              + " select ?, id, ?, 'CV Ana Ejemplo.pdf', 'PDF', 10, repeat('a', 64), true, now()"
              + " from candidate_profiles where account_id = ?",
          UUID.randomUUID(),
          "cv/" + UUID.randomUUID(),
          id);
    }
    return id;
  }

  private MockHttpServletRequestBuilder apply(UUID candidate, String job, String key) {
    MockHttpServletRequestBuilder request =
        authed(post("/api/v1/ats/jobs/" + job + "/applications"), candidate);
    return key == null ? request : request.header("Idempotency-Key", key);
  }

  private int count(String sql, Object... args) {
    return jdbc.queryForObject(sql, Integer.class, args);
  }

  @Test
  void aReadyCandidateAppliesAndEverythingIsLeftInPlace() throws Exception {
    String job = published(account(false, true));
    UUID candidate = candidate(true);

    MvcResult result =
        mvc.perform(apply(candidate, job, null))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.jobPostingId").value(job))
            .andExpect(jsonPath("$.status").value("ACTIVE"))
            .andExpect(jsonPath("$.stage").value("NEW"))
            .andExpect(jsonPath("$.version").value(1))
            .andReturn();
    String body = result.getResponse().getContentAsString();
    String id = JsonPath.read(body, "$.id");

    assertThat(body).doesNotContain(SECRET_NAME).doesNotContain(SECRET_EMAIL);
    assertThat(
            count(
                "select count(*) from applications a join candidate_cv_files f on f.id = a.cv_file_id"
                    + " where a.id = ?::uuid and f.is_current",
                id))
        .as("con el CV vigente")
        .isEqualTo(1);
    assertThat(
            count(
                "select count(*) from application_status_history where application_id = ?::uuid",
                id))
        .isEqualTo(1);
    // TD-040: la copia de lo que el candidato presentó queda con la postulación.
    assertThat(
            count(
                "select count(*) from application_profile_snapshots where application_id = ?::uuid",
                id))
        .isEqualTo(1);
    // BR-DATA-01: el evento lleva identificadores, ni el nombre, ni el contacto, ni el archivo.
    String payload =
        jdbc.queryForObject(
            "select payload::text from outbox_events where event_type = 'ats.application_submitted'"
                + " and aggregate_id = ?::uuid",
            String.class,
            id);
    assertThat(payload)
        .contains(id)
        .doesNotContain(SECRET_NAME)
        .doesNotContain(SECRET_EMAIL)
        .doesNotContain("CV Ana");
  }

  @Test
  void theSameKeyReturnsTheSameApplicationAndADifferentRequestIsAlreadyApplied() throws Exception {
    UUID recruiter = account(false, true);
    String job = published(recruiter);
    UUID candidate = candidate(true);

    String first =
        mvc.perform(apply(candidate, job, "intento-1"))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    String again =
        mvc.perform(apply(candidate, job, "intento-1"))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertThat(JsonPath.<String>read(again, "$.id")).isEqualTo(JsonPath.read(first, "$.id"));
    assertThat(
            count(
                "select count(*) from applications a join candidate_profiles p"
                    + " on p.id = a.candidate_profile_id where p.account_id = ?",
                candidate))
        .isEqualTo(1);
    assertThat(
            count(
                "select count(*) from application_profile_snapshots where application_id = ?::uuid",
                JsonPath.<String>read(first, "$.id")))
        .as("sin otra copia en matching")
        .isEqualTo(1);
    mvc.perform(apply(candidate, job, null))
        .andExpect(status().isConflict())
        .andExpect(jsonPath(ERROR_CODE).value("ALREADY_APPLIED"));
    mvc.perform(apply(candidate, published(recruiter), "intento-1"))
        .andExpect(status().isUnprocessableContent())
        .andExpect(jsonPath(ERROR_CODE).value("IDEMPOTENCY_KEY_REUSED"));
  }

  @Test
  void whatIsMissingForBrCand01IsListed() throws Exception {
    String job = published(account(false, true));

    mvc.perform(apply(candidate(false), job, null))
        .andExpect(status().isUnprocessableContent())
        .andExpect(jsonPath(ERROR_CODE).value("APPLICATION_REQUIREMENTS_MISSING"))
        .andExpect(jsonPath("$.error.details[0].field").value("CV"))
        .andExpect(jsonPath("$.error.details[0].code").value("MISSING"));

    mvc.perform(apply(account(true, false), job, null))
        .andExpect(status().isUnprocessableContent())
        .andExpect(jsonPath("$.error.details.length()").value(3));
  }

  // BR-ACCT-05: con las dos capacidades, el reclutador sigue sin poder postular a lo suyo.
  @Test
  void aRecruiterCannotApplyToTheirOwnPosting() throws Exception {
    UUID both = candidate(true);
    jdbc.update("update accounts set is_recruiter_enabled = true where id = ?", both);

    mvc.perform(apply(both, published(both), null))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath(ERROR_CODE).value("SELF_APPLICATION"));
  }

  @Test
  void aDraftDoesNotExistAndAClosedPostingDoesNotAcceptApplications() throws Exception {
    UUID recruiter = account(false, true);
    UUID candidate = candidate(true);
    MvcResult draft =
        mvc.perform(json(post(POSTINGS), recruiter, POSTING))
            .andExpect(status().isCreated())
            .andReturn();
    String draftId = JsonPath.read(draft.getResponse().getContentAsString(), "$.id");
    String closed = published(recruiter);
    transition(recruiter, closed, "CLOSED", 3);

    mvc.perform(apply(candidate, draftId, null)).andExpect(status().isNotFound());
    mvc.perform(apply(candidate, UUID.randomUUID().toString(), null))
        .andExpect(status().isNotFound());
    mvc.perform(apply(candidate, "no-es-un-uuid", null)).andExpect(status().isNotFound());
    mvc.perform(apply(candidate, closed, null))
        .andExpect(status().isConflict())
        .andExpect(jsonPath(ERROR_CODE).value("INVALID_STATE"));
  }

  @Test
  void anAccountWithoutTheCandidateCapabilityCannotApply() throws Exception {
    UUID recruiter = account(false, true);

    mvc.perform(apply(recruiter, published(account(false, true)), null))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath(ERROR_CODE).value("FORBIDDEN_CAPABILITY"));
  }

  @Test
  void aMalformedKeyIsRejectedBeforeAnything() throws Exception {
    String job = published(account(false, true));

    mvc.perform(apply(candidate(true), job, "con espacios"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath(ERROR_CODE).value("VALIDATION_FAILED"));
  }
}
