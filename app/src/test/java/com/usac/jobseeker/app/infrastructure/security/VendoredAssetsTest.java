package com.usac.jobseeker.app.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;

/**
 * HTMX se sirve desde el propio servidor (CSP sin 'unsafe-inline' ni orígenes externos). Esta
 * prueba fija su huella: si alguien edita el archivo, falla. Para actualizarlo, bajar htmx.org del
 * registro de npm, comprobar su `integrity` publicada y cambiar la huella y la versión de aquí.
 */
class VendoredAssetsTest {

  /**
   * htmx.org 2.0.11, `dist/htmx.min.js`; licencia Zero-Clause BSD (sin obligación de atribución).
   */
  private static final String HTMX_2_0_11_SHA256 =
      "d6fdc75f204e6bdefa99b69bf1e6d4ac69b8a364f77929f45c13476b4000f717";

  @Test
  void htmxIsTheExactVersionThatWasVerified() throws Exception {
    try (InputStream in = getClass().getResourceAsStream("/static/js/htmx.min.js")) {
      assertThat(in).isNotNull();
      byte[] digest = MessageDigest.getInstance("SHA-256").digest(in.readAllBytes());
      assertThat(HexFormat.of().formatHex(digest)).isEqualTo(HTMX_2_0_11_SHA256);
    }
  }
}
