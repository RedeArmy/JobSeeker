package com.usac.jobseeker.app.infrastructure.security.liveness;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Base64;
import java.util.Random;
import org.junit.jupiter.api.Test;

class RefreshTokenCipherTest {

  private static String key(long seed) {
    byte[] bytes = new byte[32];
    new Random(seed).nextBytes(bytes);
    return Base64.getEncoder().encodeToString(bytes);
  }

  private static RefreshTokenCipher cipher(String active, String keys) {
    return RefreshTokenCipher.from(new EncryptionProperties(active, keys));
  }

  @Test
  void aTokenRoundTripsAndNeverAppearsInThePlainStoredValue() {
    RefreshTokenCipher cipher = cipher("k1", "k1:" + key(1));

    String stored = cipher.encrypt("refresh-token-secreto", "enlace-1");

    assertThat(stored).startsWith("k1.").doesNotContain("refresh-token-secreto");
    assertThat(cipher.decrypt(stored, "enlace-1")).isEqualTo("refresh-token-secreto");
  }

  @Test
  void theSameTokenEncryptsDifferentlyEachTime() {
    RefreshTokenCipher cipher = cipher("k1", "k1:" + key(1));

    assertThat(cipher.encrypt("t", "a")).isNotEqualTo(cipher.encrypt("t", "a"));
  }

  @Test
  void aValueCopiedToAnotherLinkDoesNotDecrypt() {
    RefreshTokenCipher cipher = cipher("k1", "k1:" + key(1));
    String stored = cipher.encrypt("t", "enlace-1");

    assertThatThrownBy(() -> cipher.decrypt(stored, "enlace-2"))
        .isInstanceOf(RefreshTokenCipher.UndecipherableTokenException.class);
  }

  @Test
  void aTamperedValueDoesNotDecrypt() {
    RefreshTokenCipher cipher = cipher("k1", "k1:" + key(1));
    String stored = cipher.encrypt("token-largo-para-que-haya-bytes", "a");
    String tampered =
        stored.substring(0, stored.length() - 2) + (stored.endsWith("AA") ? "BB" : "AA");

    assertThatThrownBy(() -> cipher.decrypt(tampered, "a"))
        .isInstanceOf(RefreshTokenCipher.UndecipherableTokenException.class);
  }

  @Test
  void rotatingTheKeyKeepsOldValuesReadableUntilTheOldKeyIsRetired() {
    String oldValue = cipher("k1", "k1:" + key(1)).encrypt("t", "a");

    RefreshTokenCipher rotated = cipher("k2", "k1:" + key(1) + ",k2:" + key(2));
    assertThat(rotated.decrypt(oldValue, "a")).isEqualTo("t");
    assertThat(rotated.encrypt("t", "a")).startsWith("k2.");

    RefreshTokenCipher retired = cipher("k2", "k2:" + key(2));
    assertThatThrownBy(() -> retired.decrypt(oldValue, "a"))
        .isInstanceOf(RefreshTokenCipher.UndecipherableTokenException.class)
        .hasMessageContaining("retirada");
  }

  @Test
  void withoutKeysItIsUnavailableAndRefusesToEncrypt() {
    RefreshTokenCipher none = cipher(null, null);

    assertThat(none.available()).isFalse();
    assertThatThrownBy(() -> none.encrypt("t", "a")).isInstanceOf(IllegalStateException.class);
  }

  @Test
  void aMisconfigurationFailsWithAMessageThatSaysWhatIsWrong() {
    assertThatThrownBy(() -> cipher("k1", "k1:" + Base64.getEncoder().encodeToString(new byte[16])))
        .hasMessageContaining("32 bytes");
    assertThatThrownBy(() -> cipher("k1", "k1:no es base64!!")).hasMessageContaining("base64");
    assertThatThrownBy(() -> cipher("k9", "k1:" + key(1))).hasMessageContaining("ACTIVE_KID");
    assertThatThrownBy(() -> cipher("k1", "sin-separador")).hasMessageContaining("kid:clave");
    assertThatThrownBy(() -> cipher("a.b", "a.b:" + key(1))).hasMessageContaining("kid");
  }
}
