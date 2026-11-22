package com.usac.jobseeker.app.infrastructure.security.liveness;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.usac.jobseeker.app.infrastructure.security.ClavarisProperties;
import com.usac.jobseeker.app.infrastructure.security.SessionProperties;
import com.usac.jobseeker.app.infrastructure.security.liveness.LivenessCoordinator.Verdict;
import com.usac.jobseeker.app.infrastructure.security.liveness.RefreshTokenClient.Result;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/** El cableado de la prueba de vida: qué se exige al arrancar y cómo se acota el margen. */
class LivenessConfigurationTest {

  private static final String KEY = Base64.getEncoder().encodeToString(new byte[32]);

  private final LivenessConfiguration configuration = new LivenessConfiguration();

  private static ClavarisProperties clavaris(String issuer) {
    return new ClavarisProperties(
        issuer,
        "id",
        "secreto",
        List.of("openid"),
        Duration.ofSeconds(2),
        Duration.ofSeconds(5),
        Duration.ofSeconds(10));
  }

  @Test
  void withClavarisConfiguredAndNoEncryptionKeyTheApplicationRefusesToStartAndSaysWhatIsMissing() {
    assertThatThrownBy(
            () ->
                configuration.refreshTokenCipher(
                    new EncryptionProperties(null, null), clavaris("http://clavaris/o/x")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("TOKEN_ENCRYPTION_KEYS");
  }

  @Test
  void withClavarisConfiguredAndAKeyItStartsAndCanEncrypt() {
    RefreshTokenCipher cipher =
        configuration.refreshTokenCipher(
            new EncryptionProperties("k1", "k1:" + KEY), clavaris("http://clavaris/o/x"));

    assertThat(cipher.available()).isTrue();
  }

  @Test
  void withoutClavarisConfiguredItStartsEvenWithoutAKeySoTheLoginCanAnswerNotConfigured() {
    assertThatCode(
            () ->
                configuration.refreshTokenCipher(
                    new EncryptionProperties(null, null), clavaris("")))
        .doesNotThrowAnyException();
    assertThat(
            configuration
                .refreshTokenCipher(new EncryptionProperties(null, null), clavaris(""))
                .available())
        .isFalse();
  }

  @Test
  void aMalformedKeyStopsTheStartupInsteadOfFailingOnTheFirstLogin() {
    assertThatThrownBy(
            () ->
                configuration.refreshTokenCipher(
                    new EncryptionProperties("k1", "k1:no-es-base64!!"), clavaris("")))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void theMarginWithoutClavarisNeverExceedsTheAbsoluteLimitOfTheSession() {
    // ADR-0020, regla 2: con límite absoluto de 1 h y margen configurado de 2 h, manda el límite.
    assertThat(sessionAliveAfter(Duration.ofHours(1), Duration.ofHours(2), Duration.ofMinutes(61)))
        .isEqualTo(Verdict.CLOSED);
  }

  @Test
  void aMarginShorterThanTheAbsoluteLimitIsRespectedAsConfigured() {
    assertThat(sessionAliveAfter(Duration.ofHours(8), Duration.ofHours(2), Duration.ofMinutes(61)))
        .isEqualTo(Verdict.ALIVE);
    assertThat(sessionAliveAfter(Duration.ofHours(8), Duration.ofHours(2), Duration.ofMinutes(121)))
        .isEqualTo(Verdict.CLOSED);
  }

  private Verdict sessionAliveAfter(Duration absolute, Duration margin, Duration elapsed) {
    Instant start = Instant.parse("2026-11-22T12:00:00Z");
    Clock[] clock = {Clock.fixed(start, ZoneOffset.UTC)};
    Clock movable =
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
            return clock[0].instant();
          }
        };
    LivenessStore store =
        new LivenessStore(
            RedisForTests.template(),
            RefreshTokenCipher.from(new EncryptionProperties("k1", "k1:" + KEY)),
            Duration.ofHours(8));
    RefreshTokenClient clavarisDown = new FakeClient();
    LivenessProperties liveness =
        new LivenessProperties(
            Duration.ofMinutes(10),
            margin,
            true,
            false,
            Duration.ofSeconds(45),
            Duration.ofSeconds(30),
            Duration.ofSeconds(1),
            Duration.ofSeconds(2),
            Duration.ofSeconds(5));
    @SuppressWarnings("unchecked")
    ObjectProvider<MeterRegistry> meters = mock(ObjectProvider.class);
    LivenessCoordinator coordinator =
        configuration.livenessCoordinator(
            store,
            clavarisDown,
            liveness,
            new SessionProperties(Duration.ofMinutes(30), absolute, "SESSION", false),
            movable,
            meters);

    String link = store.begin("token", start);
    clock[0] = Clock.fixed(start.plus(elapsed), ZoneOffset.UTC);
    return coordinator.verify(link);
  }

  /** Clavaris no responde: toda renovación es un fallo transitorio. */
  private static final class FakeClient implements RefreshTokenClient {
    @Override
    public Result refresh(String refreshToken) {
      return new Result.Transient("http_503");
    }

    @Override
    public boolean revoke(String refreshToken) {
      return false;
    }
  }
}
