package com.usac.jobseeker.auth.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.usac.jobseeker.common.domain.model.AccountId;
import com.usac.jobseeker.common.domain.model.Capability;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AccountTest {

  private static final AccountId ID = new AccountId(UUID.randomUUID());

  @Test
  void anAccountWithoutCapabilitiesIsRejected() {
    // BR-ACCT-01: ninguna cuenta existe sin al menos una capacidad.
    assertThatThrownBy(() -> new Account(ID, Set.of(), DeletionStatus.ACTIVE))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void onlyAnActiveAccountCanAct() {
    assertThat(new Account(ID, Set.of(Capability.CANDIDATE), DeletionStatus.ACTIVE).canAct())
        .isTrue();
    assertThat(
            new Account(ID, Set.of(Capability.CANDIDATE), DeletionStatus.PENDING_DELETION).canAct())
        .isFalse();
    assertThat(new Account(ID, Set.of(Capability.CANDIDATE), DeletionStatus.ANONYMIZED).canAct())
        .isFalse();
  }

  @Test
  void newAccountsStartAsCandidatesOnly() {
    assertThat(Account.initialCapabilities()).containsExactly(Capability.CANDIDATE);
  }

  @Test
  void theSubjectOfTheTokenBecomesTheAccountId() {
    UUID uuid = UUID.randomUUID();
    assertThat(AccountId.fromSubject(uuid.toString()).value()).isEqualTo(uuid);
    assertThatThrownBy(() -> AccountId.fromSubject("no-es-un-uuid"))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
