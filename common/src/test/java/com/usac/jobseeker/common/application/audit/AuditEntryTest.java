package com.usac.jobseeker.common.application.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.usac.jobseeker.common.domain.model.AccountId;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Lo que entra al registro de auditoría no puede llevar datos personales (BR-DATA-01). */
class AuditEntryTest {

  private static final AccountId ACTOR = new AccountId(UUID.randomUUID());

  private static AuditEntry entry(String action, String type, Map<String, String> details) {
    return new AuditEntry(ACTOR, action, type, UUID.randomUUID(), details);
  }

  @Test
  void aSimpleEntryIsAccepted() {
    var e = entry("RECRUITER_VERIFICATION_CHANGED", "ACCOUNT", Map.of("to", "VERIFIED"));

    assertThat(e.details()).containsEntry("to", "VERIFIED");
    assertThat(e.actor()).isEqualTo(ACTOR);
  }

  @Test
  void anEntryWithoutDetailsOrTargetIsValid() {
    assertThat(new AuditEntry(ACTOR, "LOGIN_FAILED", "SESSION", null, null).details()).isEmpty();
  }

  @Test
  void theActionAndTheTargetTypeAreUppercaseCodes() {
    for (String bad : new String[] {null, "", "ab", "minuscula", "Con Espacio", "X".repeat(61)}) {
      assertThatThrownBy(() -> entry(bad, "ACCOUNT", Map.of()))
          .as("acción " + bad)
          .isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(() -> entry("ACTION_OK", bad, Map.of()))
          .as("tipo " + bad)
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  // BR-DATA-01: no hay dónde escribir un nombre, un correo ni un texto libre.
  @Test
  void aDetailThatCouldCarryPersonalDataIsRejected() {
    for (String value :
        new String[] {
          "ana@example.com", "Ana López", "texto con espacios", "x".repeat(81), "a,b"
        }) {
      Map<String, String> details = Map.of("to", value);

      assertThatThrownBy(() -> entry("ACTION_OK", "ACCOUNT", details))
          .as(value)
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Test
  void aDetailKeyMustBeASimpleIdentifier() {
    for (String key : new String[] {"", "Con Espacio", "1inicia", "con-guion", "k".repeat(31)}) {
      Map<String, String> details = Map.of(key, "v");

      assertThatThrownBy(() -> entry("ACTION_OK", "ACCOUNT", details))
          .as(key)
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Test
  void theEntryKeepsItsOwnCopyOfTheDetails() {
    var mutable = new HashMap<String, String>();
    mutable.put("to", "VERIFIED");
    var e = entry("ACTION_OK", "ACCOUNT", mutable);

    mutable.put("to", "SUSPENDED");

    assertThat(e.details()).containsEntry("to", "VERIFIED");
    Map<String, String> exposed = e.details();

    assertThatThrownBy(() -> exposed.put("x", "y"))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void anActorIsRequired() {
    assertThatThrownBy(() -> new AuditEntry(null, "ACTION_OK", "ACCOUNT", null, Map.of()))
        .isInstanceOf(NullPointerException.class);
  }
}
