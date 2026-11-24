package com.usac.jobseeker.app.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
class CandidateVisibilityScreenIntegrationTest {

  private static final String PAGE = "/app/candidate-visibility";

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

  private UUID candidateWithProfile() throws Exception {
    UUID id = candidateWithProfessional();
    mvc.perform(
            put("/app/candidate-profile/details")
                .with(as(id))
                .with(csrf())
                .param("contactEmail", "ana@example.test")
                .param("version", "0"))
        .andExpect(status().isOk());
    return id;
  }

  private UUID otherAccount() {
    UUID id = UUID.randomUUID();
    jdbc.update("insert into accounts (id, is_recruiter_enabled) values (?, true)", id);
    return id;
  }

  private UUID candidateWithProfessional() throws Exception {
    UUID id = UUID.randomUUID();
    jdbc.update("insert into accounts (id, is_candidate_enabled) values (?, true)", id);
    mvc.perform(write("/app/profile/identity", id).param("fullName", "Ana").param("version", "0"))
        .andExpect(status().isOk());
    return id;
  }

  @Test
  void withoutACandidateProfileThePageExplainsItAndOffersNoForms() throws Exception {
    UUID id = candidateWithProfessional();

    String page = body(get(PAGE).with(as(id)));

    assertThat(page).contains("Primero crea tu").doesNotContain("<form");
  }

  @Test
  void theWholeFlowSwitchesDiscoverabilityDeclaresAvailabilityAndManagesTheList() throws Exception {
    UUID id = candidateWithProfile();
    UUID excluded = otherAccount();

    mvc.perform(write(PAGE + "/discoverability", id).param("version", "1"))
        .andExpect(status().isOk());
    mvc.perform(
            write(PAGE + "/availability", id).param("status", "OPEN_TO_WORK").param("version", "2"))
        .andExpect(status().isOk());
    mvc.perform(
            post(PAGE + "/exclusions")
                .with(as(id))
                .with(csrf())
                .param("accountId", excluded.toString()))
        .andExpect(status().isOk());

    String page = body(get(PAGE).with(as(id)));
    assertThat(page)
        .contains(excluded.toString())
        .contains("name=\"version\" value=\"3\"")
        .contains("value=\"OPEN_TO_WORK\" selected")
        .doesNotContain("checked=\"checked\"");
    mvc.perform(delete(PAGE + "/exclusions/" + excluded).with(as(id)).with(csrf()))
        .andExpect(status().isOk());
    assertThat(body(get(PAGE).with(as(id)))).contains("No has excluido a nadie");
  }

  @Test
  void theCheckboxLeftEmptyMeansNotDiscoverableAndThePageHasNoInlineStyleOrScript()
      throws Exception {
    UUID id = candidateWithProfile();

    String page = body(get(PAGE).with(as(id)));

    assertThat(page)
        .contains("checked=\"checked\"")
        .doesNotContainPattern(Pattern.compile("<[^>]+ style="))
        .doesNotContainPattern(Pattern.compile("<script>(?!</script>)"));
  }

  @Test
  void aBadAccountOrYourOwnIsA422WithOnlyTheErrorZoneAndNeverEchoesTheValue() throws Exception {
    UUID id = candidateWithProfile();

    String garbage =
        mvc.perform(
                post(PAGE + "/exclusions")
                    .with(as(id))
                    .with(csrf())
                    .param("accountId", "secreto-malo"))
            .andExpect(status().isUnprocessableContent())
            .andReturn()
            .getResponse()
            .getContentAsString();
    String self =
        mvc.perform(
                post(PAGE + "/exclusions")
                    .with(as(id))
                    .with(csrf())
                    .param("accountId", id.toString()))
            .andExpect(status().isUnprocessableContent())
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertThat(garbage)
        .contains("La cuenta")
        .doesNotContain("secreto-malo")
        .doesNotContain("<form");
    assertThat(self).contains("no puede ser tu propia cuenta");
  }

  @Test
  void aDuplicateAnUnknownAccountAndAStaleVersionReloadTheContentWithANotice() throws Exception {
    UUID id = candidateWithProfile();
    UUID excluded = otherAccount();
    mvc.perform(
            post(PAGE + "/exclusions")
                .with(as(id))
                .with(csrf())
                .param("accountId", excluded.toString()))
        .andExpect(status().isOk());

    String duplicate =
        mvc.perform(
                post(PAGE + "/exclusions")
                    .with(as(id))
                    .with(csrf())
                    .param("accountId", excluded.toString()))
            .andExpect(status().isConflict())
            .andReturn()
            .getResponse()
            .getContentAsString();
    String unknown =
        mvc.perform(
                post(PAGE + "/exclusions")
                    .with(as(id))
                    .with(csrf())
                    .param("accountId", UUID.randomUUID().toString()))
            .andExpect(status().isNotFound())
            .andReturn()
            .getResponse()
            .getContentAsString();
    String stale =
        mvc.perform(write(PAGE + "/discoverability", id).param("version", "9"))
            .andExpect(status().isConflict())
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertThat(duplicate).contains("ya estaba en tu lista").contains("<form");
    assertThat(unknown).contains("No encontramos esa cuenta");
    assertThat(stale).contains("cambió desde que lo abriste");
  }

  @Test
  void anAccountWithoutTheCandidateCapabilityGets403AndNoForms() throws Exception {
    UUID id = otherAccount();

    String page =
        mvc.perform(get(PAGE).with(as(id)))
            .andExpect(status().isForbidden())
            .andReturn()
            .getResponse()
            .getContentAsString();
    mvc.perform(write(PAGE + "/availability", id).param("status", "UNSET").param("version", "0"))
        .andExpect(status().isForbidden());

    assertThat(page).contains("no tiene habilitada").doesNotContain("<form");
  }

  @Test
  void everyMutationWithoutTheCsrfTokenIsRejectedByTheChain() throws Exception {
    UUID id = candidateWithProfile();

    mvc.perform(put(PAGE + "/discoverability").with(as(id)).param("version", "1"))
        .andExpect(status().isForbidden());
    mvc.perform(
            put(PAGE + "/availability").with(as(id)).param("status", "UNSET").param("version", "1"))
        .andExpect(status().isForbidden());
    mvc.perform(
            post(PAGE + "/exclusions")
                .with(as(id))
                .param("accountId", UUID.randomUUID().toString()))
        .andExpect(status().isForbidden());
    mvc.perform(delete(PAGE + "/exclusions/" + UUID.randomUUID()).with(as(id)))
        .andExpect(status().isForbidden());
  }
}
