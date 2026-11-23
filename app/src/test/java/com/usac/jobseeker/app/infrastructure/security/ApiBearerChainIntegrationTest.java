package com.usac.jobseeker.app.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.usac.jobseeker.app.InfrastructureTestConfiguration;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * La cadena de la API completa (ADR-0020, superficie B): los filtros reales de Spring Security,
 * tokens firmados con RSA de verdad, un emisor que sirve el JWKS y la base de datos real. Dos rutas
 * de prueba hacen visible a quién ve la aplicación; no existen en el código de producción.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({InfrastructureTestConfiguration.class, ApiBearerChainIntegrationTest.TestEndpoints.class})
class ApiBearerChainIntegrationTest {

  private static final StubIssuer ISSUER = startIssuer();
  private static final TestTokens KEYS = TestTokens.withKey("kid-integracion");
  private static final TestTokens UNPUBLISHED = TestTokens.withKey("kid-no-publicada");

  private static StubIssuer startIssuer() {
    try {
      return new StubIssuer();
    } catch (java.io.IOException failure) {
      throw new IllegalStateException(failure);
    }
  }

  @DynamicPropertySource
  static void clavaris(DynamicPropertyRegistry registry) {
    registry.add("jobseeker.auth.clavaris.issuer-uri", ISSUER::issuerUri);
    registry.add("jobseeker.auth.clavaris.client-id", () -> TestTokens.CLIENT_ID);
    registry.add("jobseeker.auth.clavaris.client-secret", () -> "secreto-de-prueba");
    registry.add("jobseeker.auth.clavaris.read-timeout", () -> "1s");
  }

  @AfterAll
  static void stop() {
    ISSUER.close();
  }

  @Autowired private MockMvc mvc;
  @Autowired private JdbcTemplate jdbc;

  @BeforeEach
  void publishTheKeys() {
    ISSUER.jwks = KEYS.jwks();
    ISSUER.jwksStatus = 200;
  }

  private TestTokens.Builder token() {
    return KEYS.token(ISSUER.issuerUri(), Instant.now());
  }

  private static MockHttpServletRequestBuilder whoAmI(String token) {
    return get("/api/v1/test/whoami").header("Authorization", "Bearer " + token);
  }

  private String sub() {
    return UUID.randomUUID().toString();
  }

  // ---- sin credencial ----

  @Test
  void withoutAnyCredentialTheApiAnswers401WithTheChallengeAndTheStandardEnvelope()
      throws Exception {
    MvcResult result =
        mvc.perform(get("/api/v1/test/whoami"))
            .andExpect(status().isUnauthorized())
            .andExpect(header().string("WWW-Authenticate", "Bearer"))
            .andExpect(jsonPath("$.error.code").value("AUTHENTICATION_REQUIRED"))
            .andExpect(jsonPath("$.error.traceId").isNotEmpty())
            .andReturn();

    assertThat(result.getResponse().getHeader("X-Trace-Id"))
        .isEqualTo(
            com.jayway.jsonpath.JsonPath.read(
                result.getResponse().getContentAsString(), "$.error.traceId"));
  }

  @ParameterizedTest
  @ValueSource(strings = {"/api/v1", "/api/v1/", "/api/v1/no-existe", "/api/v1/test/whoami/"})
  void everyPathUnderTheApiAsksForAToken(String path) throws Exception {
    mvc.perform(get(path)).andExpect(status().isUnauthorized());
  }

  @Test
  void aSchemeOtherThanBearerIsNotAToken() throws Exception {
    mvc.perform(get("/api/v1/test/whoami").header("Authorization", "Basic dXNlcjpwYXNz"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.error.code").value("AUTHENTICATION_REQUIRED"));
  }

  @Test
  void aTokenInTheQueryStringIsNotAcceptedBecauseItWouldEndUpInLogs() throws Exception {
    mvc.perform(get("/api/v1/test/whoami").queryParam("access_token", token().build()))
        .andExpect(status().isUnauthorized());
  }

  // ---- token rechazado: 401 con su código y sin decir por qué ----

  @Test
  void aGarbageTokenIsRejectedWithTheInvalidTokenChallenge() throws Exception {
    mvc.perform(whoAmI("esto-no-es-un-jwt"))
        .andExpect(status().isUnauthorized())
        .andExpect(header().string("WWW-Authenticate", "Bearer error=\"invalid_token\""))
        .andExpect(jsonPath("$.error.code").value("TOKEN_INVALID"));
  }

  @Test
  void everyKindOfBadTokenIsAnIndistinguishable401() throws Exception {
    String[] bad = {
      token().audience("otra-aplicacion").build(),
      token().expiresAt(Instant.now().minusSeconds(120)).build(),
      token().notBefore(Instant.now().plusSeconds(600)).build(),
      token().issuer("https://otro.example/o/x").build(),
      token().noScope().build(),
      token().subject("1-1-1-1-1").build(),
      token().signedBy(UNPUBLISHED).build(),
      token().buildUnsigned(),
      token().buildWithHmacUsingThePublicKey()
    };

    for (String token : bad) {
      MvcResult result =
          mvc.perform(whoAmI(token)).andExpect(status().isUnauthorized()).andReturn();

      assertThat(result.getResponse().getHeader("WWW-Authenticate"))
          .isEqualTo("Bearer error=\"invalid_token\"");
      assertThat(result.getResponse().getContentAsString())
          .contains("TOKEN_INVALID")
          .doesNotContain("expired")
          .doesNotContain("audience")
          .doesNotContain("issuer")
          .doesNotContain("signature");
    }
  }

  @Test
  void anIdTokenCannotBeUsedAsABearerEvenThoughItIsSignedByClavarisAndAddressedToUs()
      throws Exception {
    mvc.perform(whoAmI(token().noScope().build())).andExpect(status().isUnauthorized());
  }

  @Test
  void theSchemeNameIsCaseInsensitiveAsRfc6750Says() throws Exception {
    mvc.perform(
            get("/api/v1/test/whoami")
                .header("Authorization", "bearer " + token().subject(sub()).build()))
        .andExpect(status().isOk());
  }

  // ---- token válido: el actor sale de la base, nunca del token ----

  @Test
  void aValidTokenForANewAccountProvisionsItAsACandidateLikeAFirstLogin() throws Exception {
    String subject = sub();

    mvc.perform(whoAmI(token().subject(subject).build()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(subject))
        .andExpect(jsonPath("$.capabilities[0]").value("CANDIDATE"))
        .andExpect(jsonPath("$.capabilities.length()").value(1));

    assertThat(
            jdbc.queryForMap(
                "select is_candidate_enabled c, is_recruiter_enabled r from accounts where id = ?",
                UUID.fromString(subject)))
        .containsEntry("c", true)
        .containsEntry("r", false);
  }

  @Test
  void theCapabilitiesComeFromTheLocalAccountAndNeverFromTheScopesOfTheToken() throws Exception {
    String subject = sub();
    jdbc.update(
        "insert into accounts (id, is_candidate_enabled, is_recruiter_enabled) values (?, true, false)",
        UUID.fromString(subject));

    // El token pide scopes que parecen de reclutador y de administrador: no cambian nada.
    mvc.perform(whoAmI(token().subject(subject).scope("openid recruiter admin ROLE_ADMIN").build()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.capabilities.length()").value(1))
        .andExpect(jsonPath("$.capabilities[0]").value("CANDIDATE"));
  }

  @Test
  void anAccountWithBothCapabilitiesGetsBothFromTheDatabase() throws Exception {
    String subject = sub();
    jdbc.update(
        "insert into accounts (id, is_candidate_enabled, is_recruiter_enabled) values (?, true, true)",
        UUID.fromString(subject));

    mvc.perform(whoAmI(token().subject(subject).build()))
        .andExpect(jsonPath("$.capabilities.length()").value(2));
  }

  @Test
  void aCapabilityGrantedAfterTheTokenWasIssuedIsSeenOnTheVeryNextRequest() throws Exception {
    String subject = sub();
    String token = token().subject(subject).build();
    mvc.perform(whoAmI(token)).andExpect(jsonPath("$.capabilities.length()").value(1));

    jdbc.update(
        "update accounts set is_recruiter_enabled = true where id = ?", UUID.fromString(subject));

    mvc.perform(whoAmI(token)).andExpect(jsonPath("$.capabilities.length()").value(2));
  }

  @Test
  void anAccountInItsGracePeriodIsRejectedWith403AndItsOwnCode() throws Exception {
    String subject = sub();
    jdbc.update(
        "insert into accounts (id, is_candidate_enabled, deletion_status) values (?, true, 'PENDING_DELETION')",
        UUID.fromString(subject));

    mvc.perform(whoAmI(token().subject(subject).build()))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.error.code").value("ACCOUNT_PENDING_DELETION"));
  }

  @Test
  void anAnonymizedAccountIsRejectedWith403() throws Exception {
    String subject = sub();
    jdbc.update(
        "insert into accounts (id, is_candidate_enabled, deletion_status) values (?, true, 'ANONYMIZED')",
        UUID.fromString(subject));

    mvc.perform(whoAmI(token().subject(subject).build()))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.error.code").value("ACCOUNT_UNAVAILABLE"));
  }

  @Test
  void eachTokenIsTheActorOfItsOwnSubjectAndNeverOfAnotherAccount() throws Exception {
    String first = sub();
    String second = sub();

    mvc.perform(whoAmI(token().subject(first).build())).andExpect(jsonPath("$.id").value(first));
    mvc.perform(whoAmI(token().subject(second).build())).andExpect(jsonPath("$.id").value(second));
  }

  // ---- sin estado: ni cookie ni CSRF ----

  @Test
  void theApiIsStatelessItNeverSetsACookieNorNeedsACsrfToken() throws Exception {
    String token = token().subject(sub()).build();

    MvcResult authenticated =
        mvc.perform(post("/api/v1/test/echo").header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andReturn();
    MvcResult rejected =
        mvc.perform(post("/api/v1/test/echo")).andExpect(status().isUnauthorized()).andReturn();

    assertThat(authenticated.getResponse().getHeaders("Set-Cookie")).isEmpty();
    assertThat(rejected.getResponse().getHeaders("Set-Cookie")).isEmpty();
    assertThat(authenticated.getRequest().getSession(false)).isNull();
  }

  @Test
  void theResponseIsNeverCachedBecauseItDependsOnTheCredential() throws Exception {
    mvc.perform(whoAmI(token().subject(sub()).build()))
        .andExpect(
            header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")));
  }

  @Test
  void manySimultaneousRequestsWithTheSameTokenAllSucceed() throws Exception {
    // El decodificador se construye de forma perezosa y comparte la caché de claves entre hilos.
    String token = token().subject(sub()).build();
    ExecutorService pool = Executors.newFixedThreadPool(16);
    try {
      List<Future<Integer>> statuses =
          java.util.stream.IntStream.range(0, 48)
              .mapToObj(
                  i ->
                      pool.submit(
                          () -> mvc.perform(whoAmI(token)).andReturn().getResponse().getStatus()))
              .toList();
      for (Future<Integer> status : statuses) {
        assertThat(status.get(30, TimeUnit.SECONDS)).isEqualTo(200);
      }
    } finally {
      pool.shutdownNow();
    }
  }

  // ---- CORS cerrado ----

  @Test
  void aCrossOriginRequestIsRejectedEvenWithAValidToken() throws Exception {
    MvcResult result =
        mvc.perform(whoAmI(token().subject(sub()).build()).header("Origin", "https://evil.example"))
            .andExpect(status().isForbidden())
            .andReturn();

    assertThat(result.getResponse().getHeader("Access-Control-Allow-Origin")).isNull();
  }

  @Test
  void aPreflightFromAnyOriginIsRejectedWhileNoOriginIsConfigured() throws Exception {
    MvcResult result =
        mvc.perform(
                options("/api/v1/test/whoami")
                    .header("Origin", "https://evil.example")
                    .header("Access-Control-Request-Method", "GET")
                    .header("Access-Control-Request-Headers", "authorization"))
            .andExpect(status().isForbidden())
            .andReturn();

    assertThat(result.getResponse().getHeader("Access-Control-Allow-Origin")).isNull();
  }

  // ---- Clavaris no responde: no es culpa del token ----

  @Test
  void whenTheKeysOfAnUnknownKeyIdCannotBeFetchedTheApiAnswers503AndNotA401() throws Exception {
    // Un kid que no está en la caché obliga a recargar el JWKS; con Clavaris roto no se puede saber
    // si el token es bueno, y decir 401 acusaría a un token que quizá lo es.
    ISSUER.jwksStatus = 500;
    String signedWithANewKey = UNPUBLISHED.token(ISSUER.issuerUri(), Instant.now()).build();

    mvc.perform(whoAmI(signedWithANewKey))
        .andExpect(status().isServiceUnavailable())
        .andExpect(header().exists("Retry-After"))
        .andExpect(jsonPath("$.error.code").value("IDENTITY_PROVIDER_UNAVAILABLE"));
  }

  @Test
  void aTokenWhoseKeyIsAlreadyCachedKeepsWorkingWhileClavarisIsDown() throws Exception {
    String token = token().subject(sub()).build();
    mvc.perform(whoAmI(token)).andExpect(status().isOk()); // calienta la caché de claves

    ISSUER.jwksStatus = 500;

    mvc.perform(whoAmI(token)).andExpect(status().isOk());
  }

  // ---- las otras cadenas no cambian ----

  @Test
  void theWebhookChainStaysClosedWithoutASecretAndTheWebStillRedirectsToTheLogin()
      throws Exception {
    // Sin secreto configurado el receptor responde 503 y no autentica nada (ADR-0020, superficie
    // C).
    mvc.perform(post("/api/v1/webhooks/clavaris").content("{}"))
        .andExpect(status().isServiceUnavailable());
    mvc.perform(get("/app/home")).andExpect(status().is3xxRedirection());
  }

  // ---- rutas de prueba: no existen en producción ----

  @TestConfiguration(proxyBeanMethods = false)
  static class TestEndpoints {
    @Bean
    ProbeController probeController() {
      return new ProbeController();
    }
  }

  @RestController
  @TestComponent
  static class ProbeController {

    @GetMapping("/api/v1/test/whoami")
    Map<String, Object> whoAmI(HttpServletRequest request) {
      var actor = CurrentActor.from(request).orElseThrow();
      return Map.of(
          "id",
          actor.id().toString(),
          "capabilities",
          actor.capabilities().stream().map(Enum::name).sorted().toList());
    }

    @PostMapping("/api/v1/test/echo")
    Map<String, Object> echo(HttpServletRequest request) {
      return Map.of("ok", CurrentActor.from(request).isPresent());
    }
  }
}
