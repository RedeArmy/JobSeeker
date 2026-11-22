package com.usac.jobseeker.app.infrastructure.security.liveness;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/** El formato `kid:clave,kid:clave` de TOKEN_ENCRYPTION_KEYS. */
class EncryptionPropertiesTest {

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = {"   ", " , , "})
  void noKeysConfiguredMeansAnEmptyMap(String keys) {
    assertThat(new EncryptionProperties("k1", keys).parsedKeys()).isEmpty();
  }

  @Test
  void keysAreReadInDeclaredOrderWithSurroundingSpacesAndEmptyEntriesIgnored() {
    var parsed = new EncryptionProperties("k2", " k1:AAAA , ,k2:BBBB,").parsedKeys();

    assertThat(parsed)
        .containsExactly(java.util.Map.entry("k1", "AAAA"), java.util.Map.entry("k2", "BBBB"));
  }

  @Test
  void onlyTheFirstColonSeparatesTheIdFromTheKey() {
    assertThat(new EncryptionProperties("k1", "k1:abc:def").parsedKeys())
        .containsEntry("k1", "abc:def");
  }

  @ParameterizedTest
  @ValueSource(strings = {"sin-separador", ":sin-id", "k1:", "k1:ok,roto"})
  void aMalformedEntryFailsWithAMessageThatNamesTheFormat(String keys) {
    assertThatThrownBy(() -> new EncryptionProperties("k1", keys).parsedKeys())
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("kid:clave");
  }
}
