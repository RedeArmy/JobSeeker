package com.usac.jobseeker.auth.application.usecase.handleclavariswebhook;

import static org.assertj.core.api.Assertions.assertThat;

import com.usac.jobseeker.auth.domain.model.IdentityStatus;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/** Qué eventos de Clavaris tienen efecto en JobSeeker y cuál. */
class ClavarisEventTest {

  @Test
  void theVerifiedEventsMapToTheirEffect() {
    assertThat(ClavarisEvent.from("account.suspended")).contains(ClavarisEvent.SUSPENDED);
    assertThat(ClavarisEvent.from("account.reactivated")).contains(ClavarisEvent.REACTIVATED);
    assertThat(ClavarisEvent.from("account.deleted")).contains(ClavarisEvent.DELETED);
    assertThat(ClavarisEvent.from("account.banned")).contains(ClavarisEvent.BANNED);
    assertThat(ClavarisEvent.from("account.unbanned")).contains(ClavarisEvent.UNBANNED);
    assertThat(ClavarisEvent.from("refresh_token.reuse_detected"))
        .contains(ClavarisEvent.TOKEN_REUSE_DETECTED);
  }

  @Test
  void suspendedAndDeletedSetTheirIdentityStatusAndReactivatedGoesBackToActive() {
    assertThat(ClavarisEvent.SUSPENDED.identityStatus()).contains(IdentityStatus.SUSPENDED);
    assertThat(ClavarisEvent.DELETED.identityStatus()).contains(IdentityStatus.DELETED);
    assertThat(ClavarisEvent.REACTIVATED.identityStatus()).contains(IdentityStatus.ACTIVE);
  }

  @Test
  void aBanIsMirroredLikeASuspensionAndAnUnbanLikeAReactivation() {
    // Misma carga y misma cascada de revocación en Clavaris: en JobSeeker el efecto es el mismo.
    assertThat(ClavarisEvent.BANNED.identityStatus()).contains(IdentityStatus.SUSPENDED);
    assertThat(ClavarisEvent.UNBANNED.identityStatus()).contains(IdentityStatus.ACTIVE);
  }

  @Test
  void everyEventThatTakesAccessAwayClosesSessionsAndNoneThatRestoresItDoes() {
    for (ClavarisEvent event : ClavarisEvent.values()) {
      boolean takesAccessAway =
          event.identityStatus().map(status -> status != IdentityStatus.ACTIVE).orElse(true);

      assertThat(event.closesSessions()).as(event.type()).isEqualTo(takesAccessAway);
    }
  }

  @Test
  void aReusedRefreshTokenClosesSessionsButDoesNotChangeTheIdentityStatus() {
    assertThat(ClavarisEvent.TOKEN_REUSE_DETECTED.identityStatus()).isEmpty();
    assertThat(ClavarisEvent.TOKEN_REUSE_DETECTED.closesSessions()).isTrue();
  }

  @Test
  void onlyTheEventsThatTakeAccessAwayCloseSessions() {
    assertThat(ClavarisEvent.SUSPENDED.closesSessions()).isTrue();
    assertThat(ClavarisEvent.DELETED.closesSessions()).isTrue();
    assertThat(ClavarisEvent.BANNED.closesSessions()).isTrue();
    assertThat(ClavarisEvent.REACTIVATED.closesSessions()).isFalse();
    assertThat(ClavarisEvent.UNBANNED.closesSessions()).isFalse();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "account.created",
        "account.profile_updated",
        "account.email_changed",
        "account.new_device_detected",
        "account.password_changed",
        "account.session_revoked",
        "account.oauth_grants_revoked_all",
        "refresh_token.reuse_graced",
        "password_reset.requested",
        "organization.deleted",
        "account.session_revoked"
      })
  void everyOtherTypeOfTheCatalogHasNoEffect(String type) {
    assertThat(ClavarisEvent.from(type)).isEmpty();
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(
      strings = {" ", "ACCOUNT.SUSPENDED", "account.suspended ", " account.suspended", "suspended"})
  void theMatchIsExactSoNoVariantOfTheNameSlipsThrough(String type) {
    assertThat(ClavarisEvent.from(type)).isEmpty();
  }

  @Test
  void everyEventHasADistinctTypeAndTheNameRoundTrips() {
    List<String> types =
        java.util.Arrays.stream(ClavarisEvent.values()).map(ClavarisEvent::type).toList();

    assertThat(types).doesNotHaveDuplicates();
    for (ClavarisEvent event : ClavarisEvent.values()) {
      assertThat(ClavarisEvent.from(event.type())).isEqualTo(Optional.of(event));
    }
  }
}
