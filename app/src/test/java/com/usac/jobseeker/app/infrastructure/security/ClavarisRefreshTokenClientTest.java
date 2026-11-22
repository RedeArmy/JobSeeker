package com.usac.jobseeker.app.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.usac.jobseeker.app.infrastructure.security.liveness.ClavarisRefreshTokenClient;
import com.usac.jobseeker.app.infrastructure.security.liveness.LivenessProperties;
import com.usac.jobseeker.app.infrastructure.security.liveness.RefreshTokenClient.Result;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * La clasificación de la renovación contra un emisor falso (ADR-0020, regla 2 y 12): solo
 * `invalid_grant` es definitivo; todo lo demás es transitorio.
 */
class ClavarisRefreshTokenClientTest {

  private StubIssuer issuer;

  @BeforeEach
  void setUp() throws Exception {
    issuer = new StubIssuer();
  }

  @AfterEach
  void tearDown() {
    issuer.close();
  }

  private ClavarisRefreshTokenClient client(boolean retryOnce, Duration readTimeout) {
    ClavarisProperties clavaris =
        new ClavarisProperties(
            issuer.issuerUri(),
            "client-de-prueba",
            "secreto-de-prueba",
            List.of("openid"),
            Duration.ofSeconds(2),
            Duration.ofSeconds(5),
            Duration.ofSeconds(1));
    LivenessProperties liveness =
        new LivenessProperties(
            Duration.ofMinutes(10),
            Duration.ofHours(2),
            true,
            retryOnce,
            Duration.ofSeconds(45),
            Duration.ofSeconds(30),
            Duration.ofSeconds(10),
            Duration.ofSeconds(2),
            readTimeout);
    return new ClavarisRefreshTokenClient(
        new LazyClavarisClientRegistrationRepository(clavaris, Clock.systemUTC()), liveness);
  }

  @Test
  void aSuccessfulRenewalReturnsTheRotatedTokenAndSendsTheClientCredentialsInTheHeader() {
    Result result = client(false, Duration.ofSeconds(5)).refresh("rt-viejo");

    assertThat(result).isEqualTo(new Result.Renewed("rt-nuevo"));
    String request = issuer.tokenRequests.getFirst();
    String basic =
        Base64.getEncoder().encodeToString("client-de-prueba:secreto-de-prueba".getBytes());
    assertThat(request)
        .startsWith("Basic " + basic + "|")
        .contains("grant_type=refresh_token")
        .contains("refresh_token=rt-viejo");
  }

  @Test
  void aResponseWithoutASuccessorKeepsTheCurrentToken() {
    issuer.tokenBody = "{\"access_token\":\"a\"}";

    assertThat(client(false, Duration.ofSeconds(5)).refresh("rt"))
        .isEqualTo(new Result.Renewed(null));
  }

  @Test
  void invalidGrantIsTheOnlyDefinitiveFailure() {
    issuer.tokenStatus = 400;
    issuer.tokenBody = "{\"error\":\"invalid_grant\"}";

    assertThat(client(false, Duration.ofSeconds(5)).refresh("rt"))
        .isInstanceOf(Result.InvalidGrant.class);
  }

  @Test
  void aRotatedClientSecretIsNotTreatedAsASessionDeath() {
    // invalid_client es un fallo de configuración nuestro: cerrar la sesión de todos sería el
    // error.
    issuer.tokenStatus = 401;
    issuer.tokenBody = "{\"error\":\"invalid_client\"}";

    assertThat(client(false, Duration.ofSeconds(5)).refresh("rt"))
        .isEqualTo(new Result.Transient("http_401"));
  }

  @Test
  void aServerErrorAndAnUnreadableErrorAreTransient() {
    issuer.tokenStatus = 503;
    issuer.tokenBody = "<html>bad gateway</html>";

    assertThat(client(false, Duration.ofSeconds(5)).refresh("rt"))
        .isEqualTo(new Result.Transient("http_503"));
  }

  @Test
  void aTimeoutIsTransientAndDoesNotHangTheCaller() {
    issuer.tokenDelayMillis = 1500;

    long started = System.nanoTime();
    Result result = client(false, Duration.ofMillis(300)).refresh("rt");

    assertThat(result).isEqualTo(new Result.Transient("red_o_tiempo_agotado"));
    assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofMillis(1400));
  }

  @Test
  void withTheGraceWindowConfiguredATimeoutIsRetriedExactlyOnce() {
    issuer.tokenDelayMillis = 1500;

    Result result = client(true, Duration.ofMillis(300)).refresh("rt");

    assertThat(result).isEqualTo(new Result.Transient("red_o_tiempo_agotado"));
    assertThat(issuer.tokenRequests).hasSize(2);
  }

  @Test
  void anExplicitRejectionIsNeverRetriedEvenWithTheGraceWindow() {
    issuer.tokenStatus = 400;
    issuer.tokenBody = "{\"error\":\"invalid_grant\"}";

    client(true, Duration.ofSeconds(5)).refresh("rt");

    assertThat(issuer.tokenRequests).hasSize(1);
  }

  @Test
  void revokingPostsTheTokenWithTheClientCredentialsToTheAdvertisedEndpoint() {
    boolean revoked = client(false, Duration.ofSeconds(5)).revoke("rt-a-revocar");

    assertThat(revoked).isTrue();
    assertThat(issuer.revokeRequests.getFirst())
        .contains("token=rt-a-revocar")
        .contains("token_type_hint=refresh_token")
        .startsWith("Basic ");
  }

  @Test
  void whenRevocationFailsTheClientReportsItInsteadOfThrowing() {
    issuer.close();

    assertThat(client(false, Duration.ofMillis(300)).revoke("rt")).isFalse();
  }
}
