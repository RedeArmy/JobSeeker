package com.usac.jobseeker.common.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * El identificador de cuenta ES el `sub` de Clavaris (data-model.md §4.1): estas pruebas fijan que
 * JobSeeker nunca inventa uno ni acepta algo que no sea un UUID.
 */
class AccountIdTest {

  @Test
  void aSubjectThatIsAUuidBecomesTheAccountId() {
    UUID uuid = UUID.randomUUID();

    assertThat(AccountId.fromSubject(uuid.toString()).value()).isEqualTo(uuid);
  }

  @Test
  void theUuidIsNotChangedByCaseOrByConstructionPath() {
    UUID uuid = UUID.randomUUID();

    assertThat(AccountId.fromSubject(uuid.toString().toUpperCase())).isEqualTo(new AccountId(uuid));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "",
        " ",
        "no-es-un-uuid",
        "12345",
        "fd407317-103f-4381-a14a",
        "fd407317-103f-4381-a14a-c7c15b5a3855-extra",
        "fd407317103f4381a14ac7c15b5a3855x",
        "auth0|507f1f77bcf86cd799439011",
        "persona@ejemplo.test",
        // UUID.fromString de Java es permisivo y las acepta; una identidad debe ser canónica.
        "1-1-1-1-1",
        "0-0-0-0-0",
        "fd407317-103f-4381-a14a-c7c15b5a385"
      })
  void aSubjectThatIsNotAUuidIsRejected(String subject) {
    assertThatThrownBy(() -> AccountId.fromSubject(subject))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void aNullSubjectOrValueIsRejectedWithItsName() {
    assertThatThrownBy(() -> AccountId.fromSubject(null))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("subject");
    assertThatThrownBy(() -> new AccountId(null))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("value");
  }

  @Test
  void twoIdsOfTheSameUuidAreEqualAndShareTheirHash() {
    UUID uuid = UUID.randomUUID();

    assertThat(new AccountId(uuid))
        .isEqualTo(new AccountId(uuid))
        .hasSameHashCodeAs(new AccountId(uuid));
    assertThat(new AccountId(uuid)).isNotEqualTo(new AccountId(UUID.randomUUID()));
  }

  @Test
  void itPrintsAsTheBareUuidSoLogsAndUrlsStayStable() {
    UUID uuid = UUID.randomUUID();

    assertThat(new AccountId(uuid)).hasToString(uuid.toString());
  }
}
