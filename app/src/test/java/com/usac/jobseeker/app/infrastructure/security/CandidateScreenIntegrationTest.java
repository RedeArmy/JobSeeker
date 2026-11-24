package com.usac.jobseeker.app.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.usac.jobseeker.app.InfrastructureTestConfiguration;
import java.util.UUID;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.OidcLoginRequestPostProcessor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * La pantalla del perfil de candidato contra la cadena web real (ADR-0020): flujo completo, escape
 * de lo escrito, CSP sin estilos ni scripts en línea, errores sin borrar lo tecleado y 403 sin la
 * capacidad.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(InfrastructureTestConfiguration.class)
class CandidateScreenIntegrationTest {

  private static final String PAGE = "/app/candidate-profile";

  @Autowired private MockMvc mvc;
  @Autowired private JdbcTemplate jdbc;

  private static OidcLoginRequestPostProcessor as(UUID subject) {
    return oidcLogin().idToken(token -> token.subject(subject.toString()));
  }

  private MockHttpServletRequestBuilder write(String path, UUID account) {
    return put(path).with(as(account)).with(csrf());
  }

  private String body(MockHttpServletRequestBuilder request) throws Exception {
    return mvc.perform(request).andReturn().getResponse().getContentAsString();
  }

  private UUID candidateWithProfessional() throws Exception {
    UUID id = UUID.randomUUID();
    jdbc.update("insert into accounts (id, is_candidate_enabled) values (?, true)", id);
    mvc.perform(write("/app/profile/identity", id).param("fullName", "Ana").param("version", "0"))
        .andExpect(status().isOk());
    return id;
  }

  @Test
  void theWholeFlowCreatesThenFillsEachSetAndShowsThemBack() throws Exception {
    UUID id = candidateWithProfessional();

    mvc.perform(
            write(PAGE + "/details", id)
                .param("contactEmail", "ana@example.test")
                .param("version", "0"))
        .andExpect(status().isOk());
    mvc.perform(write(PAGE + "/skills", id).param("text", "Java;4\nSQL").param("version", "1"))
        .andExpect(status().isOk());
    mvc.perform(write(PAGE + "/languages", id).param("text", "en;b2").param("version", "2"))
        .andExpect(status().isOk());
    mvc.perform(
            write(PAGE + "/work-authorizations", id).param("text", "gt, mx").param("version", "3"))
        .andExpect(status().isOk());

    String page = body(get(PAGE).with(as(id)));
    assertThat(page)
        .contains("Java;4", "en;B2", "GT, MX", "ana@example.test")
        .contains("name=\"version\" value=\"4\"");
  }

  @Test
  void whatThePersonTypesIsEscapedAndThePageHasNoInlineStyleOrScript() throws Exception {
    UUID id = candidateWithProfessional();
    mvc.perform(
            write(PAGE + "/details", id)
                .param("contactEmail", "ana@example.test")
                .param("version", "0"))
        .andExpect(status().isOk());
    mvc.perform(
            write(PAGE + "/skills", id)
                .param("text", "</textarea><script>alert(1)</script>")
                .param("version", "1"))
        .andExpect(status().isOk());

    String page = body(get(PAGE).with(as(id)));

    assertThat(page)
        .doesNotContain("<script>alert(1)")
        .contains("&lt;script&gt;")
        .doesNotContainPattern(Pattern.compile("<[^>]+ style="))
        .doesNotContainPattern(Pattern.compile("<script>(?!</script>)"));
  }

  @Test
  void aValidationErrorIsA422WithOnlyTheErrorZoneAndNeverEchoesTheValue() throws Exception {
    UUID id = candidateWithProfessional();

    var result =
        mvc.perform(
                write(PAGE + "/details", id)
                    .param("contactEmail", "secreto-malo")
                    .param("version", "0"))
            .andExpect(status().isUnprocessableContent())
            .andReturn();

    String html = result.getResponse().getContentAsString();
    assertThat(html).contains("correo").doesNotContain("secreto-malo").doesNotContain("<form");
  }

  @Test
  void aSetLineThatCannotBeReadPointsAtItsLineAndAStaleVersionReloadsTheContent() throws Exception {
    UUID id = candidateWithProfessional();
    mvc.perform(
            write(PAGE + "/details", id)
                .param("contactEmail", "ana@example.test")
                .param("version", "0"))
        .andExpect(status().isOk());

    String errors =
        mvc.perform(write(PAGE + "/skills", id).param("text", "Java;x").param("version", "1"))
            .andExpect(status().isUnprocessableContent())
            .andReturn()
            .getResponse()
            .getContentAsString();
    String reloaded =
        mvc.perform(write(PAGE + "/skills", id).param("text", "Java").param("version", "9"))
            .andExpect(status().isConflict())
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertThat(errors).contains("La línea 1 de las habilidades");
    assertThat(reloaded).contains("cambió desde que lo abriste").contains("<form");
  }

  @Test
  void anAccountWithoutTheCandidateCapabilityGets403AndNoForms() throws Exception {
    UUID id = UUID.randomUUID();
    jdbc.update("insert into accounts (id, is_recruiter_enabled) values (?, true)", id);

    String page =
        mvc.perform(get(PAGE).with(as(id)))
            .andExpect(status().isForbidden())
            .andReturn()
            .getResponse()
            .getContentAsString();
    mvc.perform(write(PAGE + "/details", id).param("contactEmail", "a@b.co").param("version", "0"))
        .andExpect(status().isForbidden());

    assertThat(page).contains("no tiene habilitada").doesNotContain("<form");
  }

  @Test
  void aMutationWithoutTheCsrfTokenIsRejectedByTheChain() throws Exception {
    UUID id = candidateWithProfessional();

    mvc.perform(
            put(PAGE + "/details")
                .with(as(id))
                .param("contactEmail", "a@b.co")
                .param("version", "0"))
        .andExpect(status().isForbidden());
  }
}
