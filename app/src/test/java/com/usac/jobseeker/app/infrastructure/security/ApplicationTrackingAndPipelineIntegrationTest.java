package com.usac.jobseeker.app.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.usac.jobseeker.common.domain.model.AccountId;
import com.usac.jobseeker.cv.application.usecase.anonymizeprofiledata.AnonymizeProfileDataCommand;
import com.usac.jobseeker.cv.application.usecase.anonymizeprofiledata.AnonymizeProfileDataUseCase;
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
 * Sub-pasos 3 y 4 del TDD de `ats` de punta a punta: retirar y repostular, el tracker del candidato
 * (también con la vacante eliminada), y el pipeline y el detalle del reclutador, con la API real,
 * Postgres y los seis módulos. Lo que los dobles no pueden: la autorización cruzada entre módulos,
 * que el candidato nunca vea el motivo de un descarte y la anonimización vista desde el pipeline.
 */
class ApplicationTrackingAndPipelineIntegrationTest extends SharedIssuerIntegrationTest {

  private static final String POSTINGS = "/api/v1/job/postings";
  private static final String TRACKER = "/api/v1/tracker/applications";
  private static final String APPLICATIONS = "/api/v1/ats/applications/";
  private static final String ERROR_CODE = "$.error.code";
  private static final String NAME = "Ana Ejemplo";
  private static final String EMAIL = "ana.ejemplo@example.test";

  @Autowired private MockMvc mvc;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private AnonymizeProfileDataUseCase anonymize;

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

  private String published(UUID owner, String title) throws Exception {
    MvcResult created =
        mvc.perform(
                json(
                    post(POSTINGS),
                    owner,
                    "{\"title\":\"" + title + "\",\"description\":\"Java\",\"version\":0}"))
            .andExpect(status().isCreated())
            .andReturn();
    String id = JsonPath.read(created.getResponse().getContentAsString(), "$.id");
    mvc.perform(
            json(
                put(POSTINGS + "/" + id + "/requirements"),
                owner,
                "{\"requirements\":[{\"type\":\"SKILL\",\"skillName\":\"Java\",\"minLevel\":3,"
                    + "\"mandatory\":true}],\"version\":1}"))
        .andExpect(status().isOk());
    mvc.perform(
            json(
                post(POSTINGS + "/" + id + "/transitions"),
                owner,
                "{\"to\":\"PUBLISHED\",\"version\":2}"))
        .andExpect(status().isOk());
    return id;
  }

  private UUID candidate() throws Exception {
    UUID id = account(true, false);
    mvc.perform(
            json(
                put("/api/v1/me/professional-profile"),
                id,
                "{\"fullName\":\"" + NAME + "\",\"headline\":\"Backend\",\"version\":0}"))
        .andExpect(status().isCreated());
    mvc.perform(
            json(
                put("/api/v1/me/candidate-profile"),
                id,
                "{\"contactEmail\":\"" + EMAIL + "\",\"version\":0}"))
        .andExpect(status().isCreated());
    jdbc.update(
        "insert into candidate_cv_files (id, candidate_profile_id, object_key, display_name,"
            + " file_type, size_bytes, sha256, is_current, uploaded_at)"
            + " select ?, id, ?, 'cv.pdf', 'PDF', 10, repeat('a', 64), true, now()"
            + " from candidate_profiles where account_id = ?",
        UUID.randomUUID(),
        "cv/" + UUID.randomUUID(),
        id);
    return id;
  }

  private String apply(UUID candidate, String job) throws Exception {
    MvcResult result =
        mvc.perform(authed(post("/api/v1/ats/jobs/" + job + "/applications"), candidate))
            .andExpect(status().isCreated())
            .andReturn();
    return JsonPath.read(result.getResponse().getContentAsString(), "$.id");
  }

  private void rejectInTheDatabase(String application, String reason) {
    jdbc.update(
        "update applications set status = 'REJECTED', rejection_reason = ?, version = version + 1"
            + " where id = ?::uuid",
        reason,
        application);
  }

  // ---- sub-paso 3: retirar y el tracker ----

  @Test
  void aCandidateWithdrawsSeesItInTheTrackerAndCanApplyAgain() throws Exception {
    String job = published(account(false, true), "Backend Java");
    UUID candidate = candidate();
    String first = apply(candidate, job);

    mvc.perform(authed(post(APPLICATIONS + first + "/withdrawal"), candidate))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("WITHDRAWN"))
        .andExpect(jsonPath("$.history.length()").value(2))
        .andExpect(jsonPath("$.history[1].by").value("CANDIDATE"));
    mvc.perform(authed(post(APPLICATIONS + first + "/withdrawal"), candidate))
        .andExpect(status().isConflict())
        .andExpect(jsonPath(ERROR_CODE).value("INVALID_TRANSITION"));
    String second = apply(candidate, job);

    mvc.perform(authed(get(TRACKER), candidate))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(2))
        .andExpect(jsonPath("$[0].id").value(second))
        .andExpect(jsonPath("$[0].job.title").value("Backend Java"))
        .andExpect(jsonPath("$[0].job.status").value("PUBLISHED"))
        .andExpect(jsonPath("$[1].status").value("WITHDRAWN"));
    assertThat(
            jdbc.queryForObject(
                "select count(*) from outbox_events where event_type ="
                    + " 'ats.application_status_changed' and aggregate_id = ?::uuid",
                Integer.class,
                first))
        .isEqualTo(1);
  }

  // Regla 2: la postulación de otro candidato no existe, ni para retirarla ni para verla.
  @Test
  void anotherCandidatesApplicationDoesNotExist() throws Exception {
    String job = published(account(false, true), "Backend");
    String theirs = apply(candidate(), job);
    UUID me = candidate();

    mvc.perform(authed(post(APPLICATIONS + theirs + "/withdrawal"), me))
        .andExpect(status().isNotFound());
    mvc.perform(authed(get(TRACKER + "/" + theirs), me)).andExpect(status().isNotFound());
    mvc.perform(authed(get(TRACKER), account(false, true)))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath(ERROR_CODE).value("FORBIDDEN_CAPABILITY"));
  }

  // BR-TRACK-03 y BR-JOB-09: el candidato ve el descarte sin su motivo, y su postulación sigue
  // en el tracker aunque la vacante se elimine; para el reclutador, ese pipeline ya no existe.
  @Test
  void theCandidateNeverSeesTheReasonAndKeepsADeletedPostingInTheTracker() throws Exception {
    UUID recruiter = account(false, true);
    String job = published(recruiter, "Backend");
    UUID candidate = candidate();
    String application = apply(candidate, job);
    rejectInTheDatabase(application, "CANDIDATE_UNRESPONSIVE");
    jdbc.update(
        "update job_postings set status = 'DELETED', deleted_at = now() where id = ?::uuid", job);

    String body =
        mvc.perform(authed(get(TRACKER + "/" + application), candidate))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("REJECTED"))
            .andExpect(jsonPath("$.job.status").value("DELETED"))
            .andExpect(jsonPath("$.job.title").value("Backend"))
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(body).doesNotContain("CANDIDATE_UNRESPONSIVE");
    mvc.perform(authed(get("/api/v1/ats/jobs/" + job + "/applications"), recruiter))
        .andExpect(status().isNotFound());
  }

  // ---- sub-paso 4: el pipeline y el detalle ----

  @Test
  void theOwnerSeesThePipelineAndTheDetailWithContactAndReasons() throws Exception {
    UUID recruiter = account(false, true);
    String job = published(recruiter, "Backend");
    String application = apply(candidate(), job);
    rejectInTheDatabase(application, "NOT_A_FIT");

    mvc.perform(
            authed(get("/api/v1/ats/jobs/" + job + "/applications?sort=date&size=5"), recruiter))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalElements").value(1))
        .andExpect(jsonPath("$.truncated").value(false))
        .andExpect(jsonPath("$.content[0].id").value(application))
        .andExpect(jsonPath("$.content[0].candidate.fullName").value(NAME))
        .andExpect(jsonPath("$.content[0].candidate.contactEmail").doesNotExist())
        .andExpect(jsonPath("$.content[0].rejectionReason").value("NOT_A_FIT"))
        // Sin Ollama en las pruebas no hay vector: el puntaje está pendiente, nunca a medias.
        .andExpect(jsonPath("$.content[0].score.status").value("PENDING"))
        .andExpect(jsonPath("$.content[0].score.overall").doesNotExist());
    mvc.perform(authed(get(APPLICATIONS + application), recruiter))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.candidate.contactEmail").value(EMAIL))
        .andExpect(jsonPath("$.rejectionReason").value("NOT_A_FIT"))
        .andExpect(jsonPath("$.history[0].toStatus").value("ACTIVE"));
    mvc.perform(
            authed(
                get("/api/v1/ats/jobs/" + job + "/applications?status=REJECTED&minScore=10"),
                recruiter))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalElements").value(0));
  }

  // Regla 1: otro reclutador no ve ni el pipeline ni el detalle, ni el contacto.
  @Test
  void anotherRecruiterSeesNothing() throws Exception {
    String job = published(account(false, true), "Backend");
    String application = apply(candidate(), job);
    UUID other = account(false, true);

    mvc.perform(authed(get("/api/v1/ats/jobs/" + job + "/applications"), other))
        .andExpect(status().isNotFound());
    mvc.perform(authed(get(APPLICATIONS + application), other)).andExpect(status().isNotFound());
    mvc.perform(authed(get(APPLICATIONS + application), account(true, false)))
        .andExpect(status().isForbidden());
  }

  @Test
  void anUnknownFilterValueIsAValidationError() throws Exception {
    UUID recruiter = account(false, true);
    String job = published(recruiter, "Backend");

    mvc.perform(authed(get("/api/v1/ats/jobs/" + job + "/applications?status=LOST"), recruiter))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath(ERROR_CODE).value("VALIDATION_FAILED"))
        .andExpect(jsonPath("$.error.details[0].field").value("status"));
    mvc.perform(authed(get("/api/v1/ats/jobs/" + job + "/applications?size=500"), recruiter))
        .andExpect(status().isBadRequest());
  }

  // BR-DATA-05: tras anonimizar, la postulación sigue en el pipeline con el marcador y sin
  // contacto.
  @Test
  void anAnonymizedCandidateStaysInThePipelineAsThePlaceholder() throws Exception {
    UUID recruiter = account(false, true);
    String job = published(recruiter, "Backend");
    UUID candidate = candidate();
    String application = apply(candidate, job);

    anonymize.execute(new AnonymizeProfileDataCommand(new AccountId(candidate)));

    String body =
        mvc.perform(authed(get(APPLICATIONS + application), recruiter))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.candidate.fullName").value("Candidato eliminado"))
            .andExpect(jsonPath("$.candidate.anonymized").value(true))
            .andExpect(jsonPath("$.candidate.contactEmail").doesNotExist())
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(body).doesNotContain(NAME).doesNotContain(EMAIL);
  }
}
