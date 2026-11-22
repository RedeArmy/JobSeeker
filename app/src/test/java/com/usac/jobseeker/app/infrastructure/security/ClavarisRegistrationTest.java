package com.usac.jobseeker.app.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.usac.jobseeker.app.InfrastructureTestConfiguration;
import java.io.IOException;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Registro del cliente de Clavaris (ADR-0020, tdd/auth-module.md §4.4) y la petición de
 * autorización que construye, contra un emisor falso que solo sirve el descubrimiento.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(InfrastructureTestConfiguration.class)
class ClavarisRegistrationTest {

  private static final StubIssuer ISSUER = start();

  private static StubIssuer start() {
    try {
      return new StubIssuer();
    } catch (java.io.IOException failure) {
      throw new IllegalStateException(failure);
    }
  }

  @DynamicPropertySource
  static void clavaris(DynamicPropertyRegistry registry) {
    registry.add("jobseeker.auth.clavaris.issuer-uri", ISSUER::issuerUri);
    registry.add("jobseeker.auth.clavaris.client-id", () -> "client-de-prueba");
    registry.add("jobseeker.auth.clavaris.client-secret", () -> "secreto-de-prueba");
  }

  @AfterAll
  static void stop() {
    ISSUER.close();
  }

  @Autowired private MockMvc mvc;

  @Test
  void theAuthorizationRequestUsesPkceS256AndTheDiscoveredEndpoint() throws Exception {
    MvcResult result = mvc.perform(get("/oauth2/authorization/clavaris")).andReturn();

    assertThat(result.getResponse().getStatus()).isEqualTo(302);
    URI location = URI.create(result.getResponse().getHeader("Location"));
    Map<String, String> query =
        UriComponentsBuilder.fromUri(location).build().getQueryParams().toSingleValueMap();

    assertThat(location.toString()).startsWith(ISSUER.issuerUri() + "/oauth2/authorize");
    // ADR-0020, regla 10: PKCE con S256, sin depender de un valor por defecto de la biblioteca.
    assertThat(query)
        .containsEntry("code_challenge_method", "S256")
        .containsKey("code_challenge")
        .containsEntry("response_type", "code")
        .containsEntry("client_id", "client-de-prueba")
        .containsKey("state")
        .containsKey("nonce")
        // Hasta el sub-paso 4.3 no se pide el refresh token: no hay dónde guardarlo cifrado.
        .hasEntrySatisfying(
            "scope", scope -> assertThat(scope).contains("openid").doesNotContain("offline_access"))
        .hasEntrySatisfying(
            "redirect_uri", uri -> assertThat(uri).endsWith("/login/oauth2/code/clavaris"));
  }

  @Test
  void aFailedDiscoveryDoesNotBreakAndIsNotRetriedBeforeTheWait() {
    AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-11-21T12:00:00Z"));
    Clock clock =
        new Clock() {
          @Override
          public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
          }

          @Override
          public Clock withZone(java.time.ZoneId zone) {
            return this;
          }

          @Override
          public Instant instant() {
            return now.get();
          }
        };
    // Nadie escucha en el puerto 1: el descubrimiento falla de inmediato.
    var unreachable =
        new ClavarisProperties(
            "http://127.0.0.1:1/o/x",
            "id",
            "secreto",
            java.util.List.of("openid"),
            Duration.ofMillis(200),
            Duration.ofMillis(200),
            Duration.ofSeconds(10));
    var repository = new LazyClavarisClientRegistrationRepository(unreachable, clock);

    assertThat(repository.findByRegistrationId("clavaris")).isNull();
    long before = System.nanoTime();
    assertThat(repository.findByRegistrationId("clavaris")).isNull();
    // Durante la espera no se vuelve a intentar: la segunda llamada es inmediata.
    assertThat(Duration.ofNanos(System.nanoTime() - before)).isLessThan(Duration.ofMillis(50));
  }

  @Test
  void anIssuerThatAdvertisesAnotherIdentityIsRejected() throws IOException {
    try (StubIssuer impostor = new StubIssuer("https://otro-emisor.example/o/x")) {
      var props =
          new ClavarisProperties(
              impostor.issuerUri(),
              "id",
              "secreto",
              java.util.List.of("openid"),
              Duration.ofSeconds(2),
              Duration.ofSeconds(2),
              Duration.ofSeconds(10));
      var repository = new LazyClavarisClientRegistrationRepository(props, Clock.systemUTC());

      // Si el `issuer` del documento no es el configurado, otro emisor se hace pasar por Clavaris.
      assertThat(repository.findByRegistrationId("clavaris")).isNull();
    }
  }

  @Test
  void aRegistrationIsResolvedOnceAndReused() throws IOException {
    try (StubIssuer issuer = new StubIssuer()) {
      var props =
          new ClavarisProperties(
              issuer.issuerUri(),
              "id",
              "secreto",
              java.util.List.of("openid", "profile"),
              Duration.ofSeconds(2),
              Duration.ofSeconds(2),
              Duration.ofSeconds(10));
      var repository = new LazyClavarisClientRegistrationRepository(props, Clock.systemUTC());

      ClientRegistration first = repository.findByRegistrationId("clavaris");
      assertThat(first).isNotNull();
      assertThat(first.getProviderDetails().getIssuerUri()).isEqualTo(issuer.issuerUri());
      assertThat(first.getProviderDetails().getConfigurationMetadata())
          .containsKey("end_session_endpoint");
      assertThat(repository.findByRegistrationId("clavaris")).isSameAs(first);
      assertThat(repository.findByRegistrationId("otro")).isNull();
    }
  }

  @Test
  void anIssuerConfiguredWithTrailingSlashesIsStillDiscovered() throws IOException {
    try (StubIssuer issuer = new StubIssuer()) {
      var props =
          new ClavarisProperties(
              issuer.issuerUri() + "///",
              "id",
              "secreto",
              java.util.List.of("openid"),
              Duration.ofSeconds(2),
              Duration.ofSeconds(2),
              Duration.ofSeconds(10));

      ClientRegistration registration =
          new LazyClavarisClientRegistrationRepository(props, Clock.systemUTC())
              .findByRegistrationId("clavaris");

      // Las barras finales no cambian el emisor: se comparan sin ellas y el valor queda
      // normalizado.
      assertThat(registration).isNotNull();
      assertThat(registration.getProviderDetails().getIssuerUri()).isEqualTo(issuer.issuerUri());
    }
  }

  @Test
  void withoutConfigurationTheRegistrationIsAbsentInsteadOfFailing() {
    var empty =
        new ClavarisProperties(
            "",
            "",
            "",
            java.util.List.of("openid"),
            Duration.ofSeconds(2),
            Duration.ofSeconds(2),
            Duration.ofSeconds(10));
    assertThat(
            new LazyClavarisClientRegistrationRepository(empty, Clock.systemUTC())
                .findByRegistrationId("clavaris"))
        .isNull();
  }
}
