package com.usac.jobseeker.app.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Clavaris está configurado solo si se conocen su emisor y las credenciales del cliente. */
class ClavarisPropertiesTest {

  private static ClavarisProperties with(String issuer, String id, String secret) {
    return new ClavarisProperties(
        issuer,
        id,
        secret,
        List.of("openid"),
        Duration.ofSeconds(2),
        Duration.ofSeconds(5),
        Duration.ofSeconds(10));
  }

  @ParameterizedTest
  @CsvSource(
      nullValues = "NULL",
      value = {
        "http://clavaris/o/x, id, secreto, true",
        "NULL, id, secreto, false",
        "'', id, secreto, false",
        "'   ', id, secreto, false",
        "http://clavaris/o/x, NULL, secreto, false",
        "http://clavaris/o/x, '', secreto, false",
        "http://clavaris/o/x, id, NULL, false",
        "http://clavaris/o/x, id, '', false",
        "http://clavaris/o/x, id, '  ', false",
        "NULL, NULL, NULL, false"
      })
  void itIsConfiguredOnlyWhenIssuerIdAndSecretAreAllPresent(
      String issuer, String id, String secret, boolean expected) {
    assertThat(with(issuer, id, secret).configured()).isEqualTo(expected);
  }
}
