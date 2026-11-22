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

  @Test
  void aStoredValueWithoutAKeyIdOrWithOneThatIsUnknownIsRejectedAsUndecipherable() {
    RefreshTokenCipher cipher = cipher("k1", "k1:" + key(1));

    for (String stored : new String[] {"sin-punto", ".solo-el-cuerpo", "k9.AAAA"}) {
      assertThatThrownBy(() -> cipher.decrypt(stored, "a"))
          .as(stored)
          .isInstanceOf(RefreshTokenCipher.UndecipherableTokenException.class);
    }
  }

  @Test
  void aValueTooShortToHoldTheNonceAndTheTagIsRejectedRatherThanCrashing() {
    RefreshTokenCipher cipher = cipher("k1", "k1:" + key(1));

    // Hasta 12 bytes (solo el nonce) se rechaza por truncado; con 13 o más hay cuerpo pero la
    // etiqueta
    // de autenticación no cuadra, y se rechaza por alterado.
    for (int length : new int[] {0, 11, 12}) {
      String stored =
          "k1." + Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[length]);

      assertThatThrownBy(() -> cipher.decrypt(stored, "a"))
          .as("longitud %d", length)
          .isInstanceOf(RefreshTokenCipher.UndecipherableTokenException.class)
          .hasMessageContaining("truncado");
    }
    for (int length : new int[] {13, 27}) {
      String stored =
          "k1." + Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[length]);

      assertThatThrownBy(() -> cipher.decrypt(stored, "a"))
          .as("longitud %d", length)
          .isInstanceOf(RefreshTokenCipher.UndecipherableTokenException.class)
          .hasMessageContaining("alterado");
    }
  }

  @Test
  void aTokenOfEveryLengthThatMattersRoundTripsIncludingTheEmptyOne() {
    RefreshTokenCipher cipher = cipher("k1", "k1:" + key(1));

    for (int length : new int[] {0, 1, 15, 16, 17, 255, 4096}) {
      String token = "x".repeat(length);

      assertThat(cipher.decrypt(cipher.encrypt(token, "enlace"), "enlace")).isEqualTo(token);
    }
  }

  @Test
  void theKeyIdIsPartOfTheStoredValueAndMustBeOneOfTheConfiguredKeys() {
    assertThat(cipher("k7", "k7:" + key(7)).encrypt("t", "a")).startsWith("k7.");
    assertThatThrownBy(() -> cipher("k1", "k1:" + key(1) + ",k2:" + key(2)).encrypt(null, "a"))
        .isInstanceOf(NullPointerException.class);
  }
}
