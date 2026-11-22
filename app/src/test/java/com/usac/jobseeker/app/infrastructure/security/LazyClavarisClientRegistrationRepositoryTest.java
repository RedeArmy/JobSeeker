package com.usac.jobseeker.app.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.oauth2.client.registration.ClientRegistration;

/**
 * El descubrimiento perezoso de Clavaris: qué exige del documento, cuánto espera tras un fallo y
 * qué conserva para el cierre de sesión (ADR-0020, regla 12).
 */
class LazyClavarisClientRegistrationRepositoryTest {

  private static final Duration RETRY = Duration.ofSeconds(10);

  private final AtomicReference<Instant> now =
      new AtomicReference<>(Instant.parse("2026-11-22T12:00:00Z"));
  private final Clock clock =
      new Clock() {
        @Override
        public ZoneId getZone() {
          return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
          return this;
        }

        @Override
        public Instant instant() {
          return now.get();
        }
      };

  private StubIssuer issuer;

  @BeforeEach
  void setUp() throws IOException {
    issuer = new StubIssuer();
  }

  @AfterEach
  void tearDown() {
    issuer.close();
  }

  private LazyClavarisClientRegistrationRepository repository() {
    return new LazyClavarisClientRegistrationRepository(
        new ClavarisProperties(
            issuer.issuerUri(),
            "id",
            "secreto",
            List.of("openid", "offline_access"),
            Duration.ofSeconds(2),
            Duration.ofSeconds(2),
            RETRY),
        clock);
  }

  @Test
  void onlyTheClavarisRegistrationIdIsServedAndNothingElseTriggersDiscovery() {
    var repository = repository();

    assertThat(repository.findByRegistrationId("otro")).isNull();
    assertThat(repository.findByRegistrationId(null)).isNull();
    assertThat(issuer.discoveryRequests.get()).isZero();
  }

  @Test
  void discoveryHappensOnceAndTheRegistrationIsReusedAfterwards() {
    var repository = repository();

    repository.findByRegistrationId("clavaris");
    repository.findByRegistrationId("clavaris");
    repository.findByRegistrationId("clavaris");

    assertThat(issuer.discoveryRequests.get()).isEqualTo(1);
  }

  @Test
  void theRegistrationCarriesTheEndpointsTheDocumentAdvertises() {
    ClientRegistration registration = repository().findByRegistrationId("clavaris");

    assertThat(registration.getProviderDetails().getTokenUri())
        .isEqualTo(issuer.issuerUri() + "/oauth2/token");
    assertThat(registration.getProviderDetails().getAuthorizationUri())
        .isEqualTo(issuer.issuerUri() + "/oauth2/authorize");
    assertThat(registration.getProviderDetails().getJwkSetUri())
        .isEqualTo(issuer.issuerUri() + "/oauth2/jwks");
    assertThat(registration.getProviderDetails().getUserInfoEndpoint().getUri())
        .isEqualTo(issuer.issuerUri() + "/userinfo");
    assertThat(registration.getProviderDetails().getConfigurationMetadata())
        .containsEntry("end_session_endpoint", issuer.issuerUri() + "/connect/logout")
        .containsEntry("revocation_endpoint", issuer.issuerUri() + "/oauth2/revoke");
    assertThat(registration.getScopes()).containsExactlyInAnyOrder("openid", "offline_access");
    assertThat(registration.getClientId()).isEqualTo("id");
  }

  @Test
  void theOptionalEndpointsAreSimplyAbsentWhenTheDocumentDoesNotAdvertiseThem() {
    issuer.advertiseRevocation = false;
    issuer.omittedFromDiscovery.addAll(List.of("userinfo_endpoint", "end_session_endpoint"));

    ClientRegistration registration = repository().findByRegistrationId("clavaris");

    assertThat(registration).isNotNull();
    assertThat(registration.getProviderDetails().getUserInfoEndpoint().getUri()).isNull();
    assertThat(registration.getProviderDetails().getConfigurationMetadata())
        .doesNotContainKeys("end_session_endpoint", "revocation_endpoint");
  }

  @ParameterizedTest
  @ValueSource(strings = {"authorization_endpoint", "token_endpoint", "jwks_uri", "issuer"})
  void aDocumentMissingARequiredKeyIsRejectedInsteadOfBuildingABrokenRegistration(String missing) {
    issuer.omittedFromDiscovery.add(missing);

    assertThat(repository().findByRegistrationId("clavaris")).isNull();
  }

  @Test
  void afterAFailureNothingIsAskedAgainUntilTheWaitHasElapsedExactly() {
    issuer.omittedFromDiscovery.add("token_endpoint");
    var repository = repository();

    repository.findByRegistrationId("clavaris");
    assertThat(issuer.discoveryRequests.get()).isEqualTo(1);

    now.set(now.get().plus(RETRY).minusSeconds(1));
    repository.findByRegistrationId("clavaris");
    assertThat(issuer.discoveryRequests.get()).as("un segundo antes de cumplirse").isEqualTo(1);

    now.set(now.get().plusSeconds(1));
    repository.findByRegistrationId("clavaris");
    assertThat(issuer.discoveryRequests.get()).as("justo al cumplirse").isEqualTo(2);
  }

  @Test
  void onceClavarisRecoversTheNextAttemptAfterTheWaitSucceedsWithoutRestarting() {
    issuer.omittedFromDiscovery.add("jwks_uri");
    var repository = repository();
    assertThat(repository.findByRegistrationId("clavaris")).isNull();

    issuer.omittedFromDiscovery.clear();
    now.set(now.get().plus(RETRY));

    assertThat(repository.findByRegistrationId("clavaris")).isNotNull();
  }

  @Test
  void anIssuerThatTheDocumentAdvertisesWithTrailingSlashesStillMatches() {
    issuer.advertisedIssuerSuffix = "//";

    assertThat(repository().findByRegistrationId("clavaris")).isNotNull();
  }

  @Test
  void anIssuerThatTheDocumentAdvertisesWithAnyOtherSuffixIsRejected() {
    issuer.advertisedIssuerSuffix = "/otro";

    assertThat(repository().findByRegistrationId("clavaris")).isNull();
  }
}
