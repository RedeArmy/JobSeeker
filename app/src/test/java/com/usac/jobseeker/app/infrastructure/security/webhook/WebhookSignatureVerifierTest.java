package com.usac.jobseeker.app.infrastructure.security.webhook;

import static org.assertj.core.api.Assertions.assertThat;

import com.usac.jobseeker.app.infrastructure.security.webhook.WebhookSignatureVerifier.Outcome;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/** La verificación de la firma de Clavaris, contra una firma calculada de forma independiente. */
class WebhookSignatureVerifierTest {

  private static final Instant NOW = Instant.parse("2026-11-22T12:00:00Z");
  private static final byte[] BODY =
      "{\"accountId\":{\"value\":\"x\"}}".getBytes(StandardCharsets.UTF_8);
  private static final String SECRET = "whsec-vigente";
  private static final String PREVIOUS = "whsec-anterior";
  private static final String HEX64 =
      "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
  private static final String HEX63 =
      "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
  private static final String HEX65 =
      "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
  private static final Duration TOLERANCE = Duration.ofMinutes(5);

  private final WebhookSignatureVerifier verifier =
      new WebhookSignatureVerifier(List.of(SECRET, PREVIOUS), TOLERANCE);

  private static String signed(Instant at, byte[] body, String... secrets) {
    return WebhookSigner.header(at, body, List.of(secrets));
  }

  @Test
  void aSignatureMadeWithTheCurrentSecretIsValid() {
    assertThat(verifier.verify(signed(NOW, BODY, SECRET), BODY, NOW)).isEqualTo(Outcome.VALID);
  }

  @Test
  void duringARotationAnySecretOfTheTwoValidates() {
    assertThat(verifier.verify(signed(NOW, BODY, PREVIOUS), BODY, NOW)).isEqualTo(Outcome.VALID);
    assertThat(verifier.verify(signed(NOW, BODY, "nuevo-desconocido", PREVIOUS), BODY, NOW))
        .as("basta con que una de varias firmas v1 case")
        .isEqualTo(Outcome.VALID);
  }

  @Test
  void aSecretThatIsNotConfiguredIsRejected() {
    assertThat(verifier.verify(signed(NOW, BODY, "otro"), BODY, NOW)).isEqualTo(Outcome.MISMATCH);
  }

  @Test
  void withNoSecretsConfiguredNothingIsEverValid() {
    var closed = new WebhookSignatureVerifier(List.of(), TOLERANCE);

    assertThat(closed.verify(signed(NOW, BODY, SECRET), BODY, NOW)).isEqualTo(Outcome.MISMATCH);
  }

  @Test
  void changingASingleByteOfTheBodyBreaksTheSignature() {
    String header = signed(NOW, BODY, SECRET);
    byte[] tampered = BODY.clone();
    tampered[3] ^= 1;

    assertThat(verifier.verify(header, tampered, NOW)).isEqualTo(Outcome.MISMATCH);
  }

  @Test
  void reserializingTheJsonBreaksTheSignatureBecauseItIsOverTheRawBytes() {
    String header = signed(NOW, BODY, SECRET);
    byte[] pretty = "{ \"accountId\": { \"value\": \"x\" } }".getBytes(StandardCharsets.UTF_8);

    assertThat(verifier.verify(header, pretty, NOW)).isEqualTo(Outcome.MISMATCH);
  }

  @Test
  void aSignatureIsBoundToItsTimestamp() {
    String header = signed(NOW, BODY, SECRET);
    String moved = header.replace("t=" + NOW.getEpochSecond(), "t=" + (NOW.getEpochSecond() + 1));

    assertThat(verifier.verify(moved, BODY, NOW)).isEqualTo(Outcome.MISMATCH);
  }

  @Test
  void theToleranceWindowIsInclusiveAndSymmetric() {
    Instant edgePast = NOW.minus(TOLERANCE);
    Instant edgeFuture = NOW.plus(TOLERANCE);

    assertThat(verifier.verify(signed(edgePast, BODY, SECRET), BODY, NOW)).isEqualTo(Outcome.VALID);
    assertThat(verifier.verify(signed(edgeFuture, BODY, SECRET), BODY, NOW))
        .isEqualTo(Outcome.VALID);
    assertThat(verifier.verify(signed(edgePast.minusSeconds(1), BODY, SECRET), BODY, NOW))
        .isEqualTo(Outcome.STALE);
    assertThat(verifier.verify(signed(edgeFuture.plusSeconds(1), BODY, SECRET), BODY, NOW))
        .isEqualTo(Outcome.STALE);
  }

  @Test
  void aTamperedBodyIsReportedAsMismatchEvenWhenTheTimestampIsAlsoStale() {
    // La firma va primero: quien no conoce el secreto no aprende nada sobre la ventana de tiempo.
    Instant old = NOW.minus(Duration.ofDays(1));
    byte[] tampered = "{}".getBytes(StandardCharsets.UTF_8);

    assertThat(verifier.verify(signed(old, BODY, SECRET), tampered, NOW))
        .isEqualTo(Outcome.MISMATCH);
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(
      strings = {
        " ",
        "garbage",
        "t=",
        "t=abc,v1=00",
        "v1=" + HEX64,
        "t=1700000000",
        "t=1700000000,v1=",
        "t=1700000000,v1=zz",
        "t=1700000000,v1=" + HEX63,
        "t=1700000000,v1=" + HEX65,
        "t=-5,v1=" + HEX64,
        "t=1234567890123,v1=" + HEX64,
        "t=1.5,v1=" + HEX64
      })
  void aMalformedHeaderIsRejectedWithoutAnException(String header) {
    assertThat(verifier.verify(header, BODY, NOW)).isEqualTo(Outcome.MALFORMED);
  }

  @Test
  void whitespaceAroundTheItemsIsTolerated() {
    String header = signed(NOW, BODY, SECRET).replace(",", ", ");

    assertThat(verifier.verify(header, BODY, NOW)).isEqualTo(Outcome.VALID);
  }

  @Test
  void upperCaseHexIsAccepted() {
    String header = signed(NOW, BODY, SECRET);
    String upper =
        header.substring(0, header.indexOf("v1=") + 3)
            + header.substring(header.indexOf("v1=") + 3).toUpperCase(java.util.Locale.ROOT);

    assertThat(verifier.verify(upper, BODY, NOW)).isEqualTo(Outcome.VALID);
  }

  @Test
  void anEmptyBodyIsSignedAndVerifiedLikeAnyOther() {
    byte[] empty = new byte[0];

    assertThat(verifier.verify(signed(NOW, empty, SECRET), empty, NOW)).isEqualTo(Outcome.VALID);
  }

  @Test
  void theSecondTimestampItemIsIgnoredSoItCannotOverrideTheSignedOne() {
    String header = signed(NOW, BODY, SECRET) + ",t=" + (NOW.getEpochSecond() + 999_999);

    assertThat(verifier.verify(header, BODY, NOW)).isEqualTo(Outcome.VALID);
  }

  @Test
  void aBareSixtyFourDigitHexWithoutTheV1PrefixIsNotASignature() {
    String valid = WebhookSigner.hex(NOW.getEpochSecond(), BODY, SECRET);

    assertThat(verifier.verify("t=" + NOW.getEpochSecond() + "," + valid, BODY, NOW))
        .isEqualTo(Outcome.MALFORMED);
  }

  @Test
  void aTwelveDigitTimestampIsWellFormedButFarOutsideTheTolerance() {
    // Doce cifras es el máximo admitido: es válido de forma y se rechaza por vigencia, no por
    // formato.
    long far = 999_999_999_999L;
    String header = "t=" + far + ",v1=" + WebhookSigner.hex(far, BODY, SECRET);

    assertThat(verifier.verify(header, BODY, NOW)).isEqualTo(Outcome.STALE);
  }

  @Test
  void aSignatureWhoseHexIsEvenButNotSixtyFourDigitsLongIsMalformed() {
    String shorter = HEX64.substring(0, 62);
    String longer = HEX64 + "aa";

    assertThat(verifier.verify("t=" + NOW.getEpochSecond() + ",v1=" + shorter, BODY, NOW))
        .isEqualTo(Outcome.MALFORMED);
    assertThat(verifier.verify("t=" + NOW.getEpochSecond() + ",v1=" + longer, BODY, NOW))
        .isEqualTo(Outcome.MALFORMED);
  }

  @Test
  void aBlankHeaderIsMalformed() {
    assertThat(verifier.verify("   ", BODY, NOW)).isEqualTo(Outcome.MALFORMED);
  }
}
