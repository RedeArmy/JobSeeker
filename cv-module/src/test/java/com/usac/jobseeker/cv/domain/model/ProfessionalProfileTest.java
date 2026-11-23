package com.usac.jobseeker.cv.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.usac.jobseeker.common.domain.model.AccountId;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ProfessionalProfileTest {

  private static final Instant T0 = Instant.parse("2026-11-23T10:00:00Z");
  private static final Instant T1 = Instant.parse("2026-11-23T11:00:00Z");
  private final ProfessionalProfileId id = new ProfessionalProfileId(UUID.randomUUID());
  private final AccountId account = new AccountId(UUID.randomUUID());

  @Test
  void aNewProfileStartsAtVersionOne() {
    var profile =
        ProfessionalProfile.create(id, account, ProfessionalIdentity.of("Ana", null, null), T0);

    assertThat(profile.version()).isEqualTo(1);
    assertThat(profile.updatedAt()).isEqualTo(T0);
  }

  @Test
  void editingProducesTheNextVersionAndLeavesTheOriginalUntouched() {
    var original =
        ProfessionalProfile.create(id, account, ProfessionalIdentity.of("Ana", null, null), T0);

    var edited = original.edit(ProfessionalIdentity.of("Ana López", "Dev", null), T1);

    assertThat(edited.version()).isEqualTo(2);
    assertThat(edited.updatedAt()).isEqualTo(T1);
    assertThat(edited.identity().fullName()).isEqualTo("Ana López");
    assertThat(edited.id()).isEqualTo(id);
    assertThat(edited.accountId()).isEqualTo(account);
    assertThat(original.version()).isEqualTo(1);
    assertThat(original.identity().fullName()).isEqualTo("Ana");
  }

  @Test
  void aSavedProfileCannotHaveVersionZero() {
    var identity = ProfessionalIdentity.of("Ana", null, null);

    assertThatThrownBy(() -> new ProfessionalProfile(id, account, identity, 0, T0))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void everyPartIsRequired() {
    var identity = ProfessionalIdentity.of("Ana", null, null);

    assertThatThrownBy(() -> new ProfessionalProfile(null, account, identity, 1, T0))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> new ProfessionalProfile(id, null, identity, 1, T0))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> new ProfessionalProfile(id, account, null, 1, T0))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> new ProfessionalProfile(id, account, identity, 1, null))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> new ProfessionalProfileId(null))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  void theEventPayloadCarriesOnlyIdentifiersTheChangeAndTheVersion() {
    String payload =
        com.usac.jobseeker.cv.domain.event.ProfileUpdated.payload(
            account, com.usac.jobseeker.cv.domain.event.ProfileUpdated.CHANGE_IDENTITY, 7);

    assertThat(payload)
        .isEqualTo("{\"accountId\":\"" + account + "\",\"change\":\"identity\",\"version\":7}");
  }
}
