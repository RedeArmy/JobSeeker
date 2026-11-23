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
 * La pantalla del perfil profesional contra la cadena web real (ADR-0020), con la sesión simulada
 * por `oidcLogin()`. Comprueba lo que importa de una interfaz renderizada en el servidor: que lo
 * que la persona escribe se **escapa**, que la página cumple la CSP sin estilos ni scripts en
 * línea, y que los errores se muestran sin borrar lo tecleado.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(InfrastructureTestConfiguration.class)
class ProfileScreenIntegrationTest {

  private static final String PAGE = "/app/profile";

  @Autowired private MockMvc mvc;
  @Autowired private JdbcTemplate jdbc;

  private static OidcLoginRequestPostProcessor as(UUID subject) {
    return oidcLogin().idToken(token -> token.subject(subject.toString()));
  }

  private String body(MockHttpServletRequestBuilder request) throws Exception {
    return mvc.perform(request).andReturn().getResponse().getContentAsString();
  }

  private MockHttpServletRequestBuilder write(MockHttpServletRequestBuilder request, UUID account) {
    return request.with(as(account)).with(csrf());
  }

  private void createProfile(UUID account) throws Exception {
    mvc.perform(
            write(put(PAGE + "/identity"), account)
                .param("fullName", "Ana López")
                .param("version", "0"))
        .andExpect(status().isOk());
  }

  private static String hiddenVersion(String html) {
    var matcher = Pattern.compile("name=\"version\" value=\"(\\d+)\"").matcher(html);
    assertThat(matcher.find()).as("hay un campo de versión").isTrue();
    return matcher.group(1);
  }

  // ---- la página ----

  @Test
  void aNewAccountSeesTheBasicDataFormWithVersionZeroAndNoSectionsYet() throws Exception {
    String html = body(get(PAGE).with(as(UUID.randomUUID())));

    assertThat(html)
        .contains("Mi perfil profesional", "Crear mi perfil", "hx-put=\"/app/profile/identity\"");
    assertThat(hiddenVersion(html)).isEqualTo("0");
    assertThat(html).doesNotContain("Experiencia laboral", "Añadir experiencia");
  }

  @Test
  void thePageMeetsTheContentSecurityPolicyWithNoInlineStylesOrScripts() throws Exception {
    UUID id = UUID.randomUUID();
    createProfile(id);
    mvc.perform(
            write(post(PAGE + "/experiences"), id)
                .param("company", "Acme")
                .param("title", "Dev")
                .param("startDate", "2022-03-01")
                .param("version", "1"))
        .andExpect(status().isOk());

    String html = body(get(PAGE).with(as(id)));

    assertThat(html).doesNotContainPattern(" style=").doesNotContainPattern("(?i)\\son[a-z]+=");
    // Todo <script> debe traer `src`: ninguno con código en el cuerpo.
    assertThat(Pattern.compile("(?i)<script(?![^>]*\\ssrc=)").matcher(html).find()).isFalse();
    assertThat(html).contains("/js/htmx.min.js", "name=\"_csrf\"");
  }

  @Test
  void thePageCarriesTheCsrfTokenForHtmxAndTheErrorHandlingConfiguration() throws Exception {
    String html = body(get(PAGE).with(as(UUID.randomUUID())));

    assertThat(html).contains("hx-headers=", "X-CSRF-TOKEN", "htmx-config", "\"code\":\"422\"");
  }

  // ---- escritura ----

  @Test
  void savingTheIdentityCreatesTheProfileAndRevealsTheSectionsWithTheNextVersion()
      throws Exception {
    UUID id = UUID.randomUUID();

    String html =
        body(
            write(put(PAGE + "/identity"), id)
                .param("fullName", "Ana López")
                .param("headline", "Dev")
                .param("version", "0"));

    assertThat(html)
        .contains(
            "Ana López",
            "Guardar",
            "Experiencia laboral",
            "Añadir certificación",
            "Añadir proyecto");
    assertThat(hiddenVersion(html)).isEqualTo("1");
    assertThat(html).doesNotContain("<html", "<body"); // es un fragmento, no una página
  }

  @Test
  void whatThePersonTypesIsEscapedAndNeverExecuted() throws Exception {
    UUID id = UUID.randomUUID();

    String html =
        body(
            write(put(PAGE + "/identity"), id)
                .param("fullName", "<script>alert(1)</script>")
                .param("headline", "\"><img src=x onerror=alert(2)>")
                .param("version", "0"));

    assertThat(html).doesNotContain("<script>alert(1)", "<img src=x");
    assertThat(html).contains("&lt;script&gt;alert(1)&lt;/script&gt;");
  }

  @Test
  void addingAnItemReturnsTheFullContentWithItAndTheNextVersion() throws Exception {
    UUID id = UUID.randomUUID();
    createProfile(id);

    String html =
        body(
            write(post(PAGE + "/experiences"), id)
                .param("company", "Acme")
                .param("title", "Backend")
                .param("startDate", "2022-03-01")
                .param("description", "Hice cosas")
                .param("version", "1"));

    assertThat(html)
        .contains(
            "Acme", "Backend", "2022-03-01", "actual", "hx-delete=\"/app/profile/experiences/");
    assertThat(html).contains("hx-confirm=\"¿Quitar esta experiencia?\"");
    assertThat(hiddenVersion(html)).isEqualTo("2");
  }

  @Test
  void theOtherThreeCollectionsAlsoAddThroughTheScreen() throws Exception {
    UUID id = UUID.randomUUID();
    createProfile(id);

    String html =
        body(
            write(post(PAGE + "/education"), id)
                .param("institution", "USAC")
                .param("degree", "Ingeniería")
                .param("level", "BACHELOR")
                .param("version", "1"));
    assertThat(html)
        .contains("USAC", "Ingeniería", "en curso", "selected", "Licenciatura o grado", "Maestría");
    assertThat(html).doesNotContain(">POSTGRAD_SPECIALIZATION<", ">BACHELOR<");

    html =
        body(
            write(post(PAGE + "/certifications"), id)
                .param("name", "AWS")
                .param("issuingOrganization", "Amazon")
                .param("credentialUrl", "https://example.com/c")
                .param("version", "2"));
    assertThat(html).contains("AWS", "Amazon", "https://example.com/c");

    html =
        body(
            write(post(PAGE + "/projects"), id)
                .param("name", "JobSeeker")
                .param("type", "PERSONAL")
                .param("version", "3"));
    assertThat(html).contains("JobSeeker", "personal");
    assertThat(hiddenVersion(html)).isEqualTo("4");
  }

  @Test
  void removingAnItemTakesItOutAndAdvancesTheVersion() throws Exception {
    UUID id = UUID.randomUUID();
    createProfile(id);
    String added =
        body(
            write(post(PAGE + "/projects"), id)
                .param("name", "Para-Borrar")
                .param("type", "WORK")
                .param("version", "1"));
    var itemId =
        Pattern.compile("hx-delete=\"/app/profile/projects/([0-9a-f-]{36})\"").matcher(added);
    assertThat(itemId.find()).isTrue();

    String html =
        body(write(delete(PAGE + "/projects/" + itemId.group(1)), id).param("version", "2"));

    assertThat(html).doesNotContain("Para-Borrar");
    assertThat(hiddenVersion(html)).isEqualTo("3");
  }

  // ---- errores ----

  @Test
  void invalidDataAnswers422WithOnlyTheErrorZoneInSpanishAndNeverTheValueTyped() throws Exception {
    UUID id = UUID.randomUUID();
    createProfile(id);

    var result =
        mvc.perform(
                write(post(PAGE + "/experiences"), id)
                    .param("company", " ")
                    .param("title", "secreto-del-usuario\u0000")
                    .param("startDate", "2022-03-01")
                    .param("endDate", "2021-01-01")
                    .param("version", "1"))
            .andExpect(status().isUnprocessableContent())
            .andReturn();

    String html = result.getResponse().getContentAsString();
    assertThat(html)
        .contains(
            "id=\"profile-errors\"",
            "La empresa es obligatorio.",
            "El puesto contiene caracteres",
            "La fecha de fin no puede ser anterior");
    assertThat(html).doesNotContain("secreto-del-usuario").doesNotContain("id=\"profile-content\"");
  }

  @Test
  void aLinkThatIsNotAWebAddressIsRejectedWithAClearMessage() throws Exception {
    UUID id = UUID.randomUUID();
    createProfile(id);

    String html =
        mvc.perform(
                write(post(PAGE + "/certifications"), id)
                    .param("name", "N")
                    .param("issuingOrganization", "O")
                    .param("credentialUrl", "javascript:alert(1)")
                    .param("version", "1"))
            .andExpect(status().isUnprocessableContent())
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertThat(html)
        .contains("El enlace de la credencial debe ser una dirección que empiece por http");
  }

  @Test
  void aStaleVersionAnswers409AndReloadsTheContentWithTheCurrentVersion() throws Exception {
    UUID id = UUID.randomUUID();
    createProfile(id);
    mvc.perform(
        write(post(PAGE + "/projects"), id)
            .param("name", "Primero")
            .param("type", "WORK")
            .param("version", "1"));

    var result =
        mvc.perform(
                write(post(PAGE + "/projects"), id)
                    .param("name", "Pisado")
                    .param("type", "WORK")
                    .param("version", "1"))
            .andExpect(status().isConflict())
            .andReturn();

    String html = result.getResponse().getContentAsString();
    assertThat(html).contains("El perfil cambió desde que lo abriste", "Primero");
    assertThat(html).doesNotContain("Pisado");
    assertThat(hiddenVersion(html)).isEqualTo("2");
  }

  @Test
  void anItemThatNoLongerExistsAnswers404AndReloadsTheContent() throws Exception {
    UUID id = UUID.randomUUID();
    createProfile(id);

    var result =
        mvc.perform(
                write(delete(PAGE + "/experiences/" + UUID.randomUUID()), id).param("version", "1"))
            .andExpect(status().isNotFound())
            .andReturn();

    assertThat(result.getResponse().getContentAsString()).contains("Ese elemento ya no existe");
    assertThat(
            jdbc.queryForObject(
                "select version from professional_profiles where account_id = ?", Long.class, id))
        .isEqualTo(1L);
  }

  @Test
  void aMissingVersionIsAValidationMessageAndNotAServerError() throws Exception {
    UUID id = UUID.randomUUID();
    createProfile(id);

    String html =
        mvc.perform(write(post(PAGE + "/projects"), id).param("name", "P").param("type", "WORK"))
            .andExpect(status().isUnprocessableContent())
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertThat(html).contains("La versión es obligatorio.");
  }

  // ---- propiedad y CSRF ----

  @Test
  void aPersonOnlyEverSeesAndChangesTheirOwnProfile() throws Exception {
    UUID ana = UUID.randomUUID();
    UUID luis = UUID.randomUUID();
    createProfile(ana);
    body(
        write(post(PAGE + "/projects"), ana)
            .param("name", "Proyecto-De-Ana")
            .param("type", "WORK")
            .param("version", "1"));

    String theirs = body(get(PAGE).with(as(luis)));

    assertThat(theirs).doesNotContain("Proyecto-De-Ana", "Ana López");
  }

  @Test
  void everyMutatingRouteOfTheScreenIsForbiddenWithoutTheCsrfToken() throws Exception {
    UUID id = UUID.randomUUID();
    mvc.perform(put(PAGE + "/identity").with(as(id)).param("fullName", "X").param("version", "0"))
        .andExpect(status().isForbidden());
    mvc.perform(post(PAGE + "/experiences").with(as(id)).param("company", "X"))
        .andExpect(status().isForbidden());
    mvc.perform(delete(PAGE + "/projects/" + UUID.randomUUID()).with(as(id)).param("version", "1"))
        .andExpect(status().isForbidden());
  }

  @Test
  void anAnonymousVisitorIsSentToTheLogin() throws Exception {
    mvc.perform(get(PAGE)).andExpect(status().isFound());
  }
}
