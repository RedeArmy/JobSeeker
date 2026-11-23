package com.usac.jobseeker.app.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.usac.jobseeker.app.InfrastructureTestConfiguration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.OidcLoginRequestPostProcessor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * La cadena web (ADR-0020, superficie A) y las dos cadenas que aún nacen cerradas, sobre el
 * contexto completo con Postgres y Redis reales. La identidad se simula con `oidcLogin()`; el flujo
 * real contra Clavaris se valida aparte, en local.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(InfrastructureTestConfiguration.class)
class WebSecurityIntegrationTest {

  @Autowired private MockMvc mvc;
  @Autowired private JdbcTemplate jdbc;

  @Autowired
  @Qualifier("requestMappingHandlerMapping")
  private RequestMappingHandlerMapping handlerMapping;

  private static OidcLoginRequestPostProcessor loggedInAs(UUID subject) {
    return oidcLogin().idToken(token -> token.subject(subject.toString()));
  }

  @Test
  void anAnonymousNavigationToAProtectedPageGoesToTheClavarisLogin() throws Exception {
    mvc.perform(get("/app/home"))
        .andExpect(status().isFound())
        .andExpect(
            header()
                .string(
                    "Location", org.hamcrest.Matchers.endsWith("/oauth2/authorization/clavaris")));
  }

  @Test
  void anAnonymousHtmxRequestGetsA401WithAnHxRedirectInsteadOfALoginPage() throws Exception {
    mvc.perform(get("/app/home").header("HX-Request", "true"))
        .andExpect(status().isUnauthorized())
        .andExpect(header().string("HX-Redirect", "/oauth2/authorization/clavaris"))
        .andExpect(jsonPath("$.error.code").value("AUTHENTICATION_REQUIRED"))
        .andExpect(jsonPath("$.error.traceId").isNotEmpty());
  }

  @Test
  void aLoggedInUserSeesTheirAccountAndTheAccountIsProvisionedAsACandidate() throws Exception {
    UUID subject = UUID.randomUUID();

    mvc.perform(get("/app/home").with(loggedInAs(subject)))
        .andExpect(status().isOk())
        .andExpect(content().string(org.hamcrest.Matchers.containsString(subject.toString())))
        .andExpect(content().string(org.hamcrest.Matchers.containsString("CANDIDATE")))
        // El token CSRF se entrega en una <meta> para que HTMX lo adjunte (ADR-0020).
        .andExpect(content().string(org.hamcrest.Matchers.containsString("name=\"_csrf\"")));

    // BR-ACCT-09: la cuenta nace con capacidad de candidato y sin la de reclutador.
    assertThat(
            jdbc.queryForMap(
                "select is_candidate_enabled c, is_recruiter_enabled r from accounts where id = ?",
                subject))
        .containsEntry("c", true)
        .containsEntry("r", false);
  }

  @Test
  void theCapabilitiesComeFromTheDatabaseNotFromTheToken() throws Exception {
    UUID subject = UUID.randomUUID();
    mvc.perform(get("/app/home").with(loggedInAs(subject))).andExpect(status().isOk());
    jdbc.update("update accounts set is_recruiter_enabled = true where id = ?", subject);

    // El token es el mismo; la capacidad nueva se ve en la siguiente petición (ADR-0020,
    // NFR-SEC-03).
    mvc.perform(get("/app/home").with(loggedInAs(subject)))
        .andExpect(content().string(org.hamcrest.Matchers.containsString("RECRUITER")));
  }

  @Test
  void anAccountPendingDeletionOrAnonymizedIsRejectedButCanStillLogOut() throws Exception {
    UUID pending = UUID.randomUUID();
    mvc.perform(get("/app/home").with(loggedInAs(pending))).andExpect(status().isOk());
    jdbc.update("update accounts set deletion_status = 'PENDING_DELETION' where id = ?", pending);
    mvc.perform(get("/app/home").with(loggedInAs(pending)))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.error.code").value("ACCOUNT_PENDING_DELETION"));
    // Salir de la sesión se permite en cualquier estado.
    mvc.perform(post("/app/logout").with(loggedInAs(pending)).with(csrf()))
        .andExpect(status().isFound());

    UUID anonymized = UUID.randomUUID();
    mvc.perform(get("/app/home").with(loggedInAs(anonymized))).andExpect(status().isOk());
    jdbc.update("update accounts set deletion_status = 'ANONYMIZED' where id = ?", anonymized);
    mvc.perform(get("/app/home").with(loggedInAs(anonymized)))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.error.code").value("ACCOUNT_UNAVAILABLE"));
  }

  @Test
  void everyMutatingRouteOfTheWebChainRejectsARequestWithoutTheCsrfToken() throws Exception {
    // ADR-0020, prueba obligatoria: recorre TODAS las rutas mutantes de /app/** y exige 403 sin
    // CSRF.
    List<String> routes =
        new ArrayList<>(List.of("/app/logout")); // el logout lo sirve el filtro, no un controlador
    for (Map.Entry<RequestMappingInfo, ?> entry : handlerMapping.getHandlerMethods().entrySet()) {
      var methods = entry.getKey().getMethodsCondition().getMethods();
      boolean mutating =
          methods.stream()
              .anyMatch(
                  m -> Set.of(HttpMethod.POST.name(), "PUT", "PATCH", "DELETE").contains(m.name()));
      if (mutating && entry.getKey().getPathPatternsCondition() != null) {
        for (String pattern : entry.getKey().getPathPatternsCondition().getPatternValues()) {
          if (pattern.startsWith("/app/")) {
            routes.add(pattern.replaceAll("\\{[^}]+}", "1"));
          }
        }
      }
    }
    assertThat(routes).contains("/app/logout");
    for (String route : routes) {
      for (HttpMethod method :
          List.of(HttpMethod.POST, HttpMethod.PUT, HttpMethod.PATCH, HttpMethod.DELETE)) {
        mvc.perform(
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request(
                        method, route)
                    .with(loggedInAs(UUID.randomUUID())))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.error.code").value("CSRF_TOKEN_INVALID"));
      }
    }
  }

  @Test
  void loggingOutWithTheCsrfTokenEndsTheSession() throws Exception {
    mvc.perform(post("/app/logout").with(loggedInAs(UUID.randomUUID())).with(csrf()))
        .andExpect(status().isFound())
        .andExpect(header().string("Location", "/"));
  }

  @Test
  void theWebChainSendsTheSecurityHeaders() throws Exception {
    MvcResult result =
        mvc.perform(get("/app/home").with(loggedInAs(UUID.randomUUID())).secure(true)).andReturn();
    var response = result.getResponse();

    // CSP estricta sin 'unsafe-inline' (ADR-0020): HTMX sin eval y sin estilos ni scripts en línea.
    assertThat(response.getHeader("Content-Security-Policy"))
        .contains("default-src 'self'", "script-src 'self'", "frame-ancestors 'none'")
        .doesNotContain("unsafe-inline", "unsafe-eval");
    assertThat(response.getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
    assertThat(response.getHeader("X-Frame-Options")).isEqualTo("DENY");
    assertThat(response.getHeader("Referrer-Policy")).isEqualTo("strict-origin-when-cross-origin");
    assertThat(response.getHeader("Strict-Transport-Security")).contains("max-age=31536000");
    assertThat(response.getHeader("Permissions-Policy")).contains("camera=()");
    assertThat(response.getHeader("X-Trace-Id")).isNotBlank();
  }

  @Test
  void theApiRequiresABearerAndTheWebhookChainIsClosedWithoutASecret() throws Exception {
    // La cadena de la API se prueba a fondo en ApiBearerChainIntegrationTest; aquí solo que la web
    // y la API siguen siendo superficies distintas y que la de webhooks, sin secreto configurado,
    // responde 503 (la firma se prueba en
    // ClavarisWebhookIntegrationTest).
    mvc.perform(get("/api/v1/anything"))
        .andExpect(status().isUnauthorized())
        .andExpect(header().string("WWW-Authenticate", "Bearer"))
        .andExpect(jsonPath("$.error.code").value("AUTHENTICATION_REQUIRED"));
    mvc.perform(post("/api/v1/webhooks/clavaris").content("{}"))
        .andExpect(status().isServiceUnavailable());
  }

  @Test
  void undeclaredRoutesAreDeniedAndOperationalEndpointsStayReachable() throws Exception {
    mvc.perform(get("/ruta-no-declarada")).andExpect(status().is3xxRedirection());
    mvc.perform(get("/ruta-no-declarada").with(loggedInAs(UUID.randomUUID())))
        .andExpect(status().isForbidden());
    mvc.perform(get("/actuator/health/liveness")).andExpect(status().isOk());
    mvc.perform(get("/actuator/prometheus")).andExpect(status().isOk());
    mvc.perform(get("/js/htmx.min.js")).andExpect(status().isOk());
  }
}
