package com.usac.jobseeker.app.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.usac.jobseeker.app.InfrastructureTestConfiguration;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * La cuenta se resuelve una sola vez por petición aunque `ActorFilter` y varios casos de uso la
 * necesiten (TD-004): se cuentan las llamadas que llegan a la resolución real, la que consulta la
 * base de datos. Antes eran 2 por una lectura y 3 por el reemplazo de un conjunto.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
@Import(InfrastructureTestConfiguration.class)
class ActorResolutionPerRequestIntegrationTest {

  private static final String PROFILE = "/api/v1/me/professional-profile";
  private static final String CANDIDATE = "/api/v1/me/candidate-profile";
  private static final String BASE = "{\"contactEmail\":\"ana@example.test\",\"version\":%d}";

  private static final StubIssuer ISSUER = startIssuer();
  private static final TestTokens KEYS = TestTokens.withKey("kid-actor");

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
  }

  @AfterAll
  static void stop() {
    ISSUER.close();
  }

  @org.springframework.test.context.bean.override.mockito.MockitoSpyBean(
      name = "resolveActorUseCase")
  private com.usac.jobseeker.auth.application.usecase.resolveactor.ResolveActorUseCase
      realResolution;

  @Autowired private MockMvc mvc;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private MeterRegistry meters;

  @Autowired
  private com.usac.jobseeker.cv.application.usecase.getvisibleavailability
          .GetVisibleAvailabilityUseCase
      visibleAvailability;

  @BeforeEach
  void publishTheKeys() {
    ISSUER.jwks = KEYS.jwks();
    ISSUER.jwksStatus = 200;
  }

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

  private UUID candidate() {
    UUID id = UUID.randomUUID();
    jdbc.update("insert into accounts (id, is_candidate_enabled) values (?, true)", id);
    return id;
  }

  private UUID recruiter() {
    UUID id = UUID.randomUUID();
    jdbc.update("insert into accounts (id, is_recruiter_enabled) values (?, true)", id);
    return id;
  }

  /** Crea el perfil base (versión 0 → 1), que toda colección necesita. */
  private void createProfile(UUID account) throws Exception {
    mvc.perform(json(put(PROFILE), account, "{\"fullName\":\"Ana López\",\"version\":0}"))
        .andExpect(status().isCreated());
  }

  private UUID withProfile() throws Exception {
    UUID id = candidate();
    createProfile(id);
    mvc.perform(json(put(CANDIDATE), id, BASE.formatted(0))).andExpect(status().isCreated());
    return id;
  }

  private int databaseLookups() {
    return org.mockito.Mockito.mockingDetails(realResolution).getInvocations().size();
  }

  @Test
  void readingTheCandidateProfileResolvesTheAccountOnce() throws Exception {
    UUID id = withProfile();
    org.mockito.Mockito.clearInvocations(realResolution);

    mvc.perform(authed(get(CANDIDATE), id)).andExpect(status().isOk());

    assertThat(databaseLookups()).isEqualTo(1);
  }

  @Test
  void replacingASetResolvesTheAccountOnce() throws Exception {
    UUID id = withProfile();
    org.mockito.Mockito.clearInvocations(realResolution);

    mvc.perform(
            json(
                put(CANDIDATE + "/skills"), id, "{\"skills\":[{\"name\":\"Java\"}],\"version\":1}"))
        .andExpect(status().isOk());

    assertThat(databaseLookups()).isEqualTo(1);
  }

  @Test
  void excludingAnAccountResolvesTheCallerOnceAndTheExcludedOneOnce() throws Exception {
    UUID id = withProfile();
    UUID excluded = recruiter();
    org.mockito.Mockito.clearInvocations(realResolution);

    mvc.perform(
            json(
                post(CANDIDATE + "/availability-exclusions"),
                id,
                "{\"accountId\":\"%s\"}".formatted(excluded)))
        .andExpect(status().isCreated());

    assertThat(databaseLookups()).isEqualTo(2);
  }

  @Test
  void aSecondRequestResolvesTheAccountAgainBecauseNothingIsRememberedBetweenRequests()
      throws Exception {
    UUID id = withProfile();
    org.mockito.Mockito.clearInvocations(realResolution);

    mvc.perform(authed(get(CANDIDATE), id)).andExpect(status().isOk());
    mvc.perform(authed(get(CANDIDATE), id)).andExpect(status().isOk());

    assertThat(databaseLookups()).isEqualTo(2);
  }

  @Test
  void aFirstRequestOfAnUnprovisionedAccountStillProvisionsItAndSucceeds() throws Exception {
    UUID id = UUID.randomUUID();

    mvc.perform(authed(get(PROFILE), id)).andExpect(status().isOk());

    assertThat(jdbc.queryForObject("select count(*) from accounts where id = ?", Integer.class, id))
        .isOne();
  }
}
