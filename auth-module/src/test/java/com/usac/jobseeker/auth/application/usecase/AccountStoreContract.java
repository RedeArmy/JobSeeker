package com.usac.jobseeker.auth.application.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.usac.jobseeker.auth.application.usecase.provisionaccount.AccountProvisioningPort;
import com.usac.jobseeker.auth.application.usecase.resolveactor.ActorLookupPort;
import com.usac.jobseeker.auth.domain.model.Account;
import com.usac.jobseeker.auth.domain.model.DeletionStatus;
import com.usac.jobseeker.common.domain.model.AccountId;
import com.usac.jobseeker.common.domain.model.Capability;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * Lo que los puertos de cuentas prometen a los casos de uso, con independencia de la tecnología que
 * los implemente. La ejecutan el almacén en memoria y el adaptador JDBC: si cambiar de tecnología
 * rompe una promesa, rompe aquí y no en producción.
 *
 * <p>Cada prueba usa identificadores nuevos, así que el almacén puede compartirse entre pruebas.
 */
public abstract class AccountStoreContract {

  protected abstract AccountProvisioningPort provisioning();

  protected abstract ActorLookupPort lookup();

  private static AccountId newId() {
    return new AccountId(UUID.randomUUID());
  }

  @Test
  void anUnknownAccountIsAbsentFromBothPorts() {
    AccountId unknown = newId();

    assertThat(provisioning().findById(unknown)).isEmpty();
    assertThat(lookup().findById(unknown)).isEmpty();
  }

  @Test
  void theFirstInsertCreatesTheAccountAndSaysSo() {
    AccountId id = newId();

    boolean created = provisioning().insertIfAbsent(id, EnumSet.of(Capability.CANDIDATE));

    assertThat(created).isTrue();
  }

  @Test
  void aSecondInsertCreatesNothingAndSaysSo() {
    AccountId id = newId();
    provisioning().insertIfAbsent(id, EnumSet.of(Capability.CANDIDATE));

    boolean createdAgain = provisioning().insertIfAbsent(id, EnumSet.of(Capability.CANDIDATE));

    assertThat(createdAgain).isFalse();
  }

  @Test
  void aSecondInsertNeverOverwritesWhatWasGrantedBefore() {
    AccountId id = newId();
    provisioning().insertIfAbsent(id, EnumSet.of(Capability.CANDIDATE));

    provisioning().insertIfAbsent(id, EnumSet.of(Capability.RECRUITER));

    assertThat(lookup().findById(id).orElseThrow().capabilities())
        .containsExactly(Capability.CANDIDATE);
  }

  @Test
  void aStoredAccountReturnsExactlyTheCapabilitiesItWasCreatedWith() {
    for (Set<Capability> capabilities :
        List.of(
            EnumSet.of(Capability.CANDIDATE),
            EnumSet.of(Capability.RECRUITER),
            EnumSet.allOf(Capability.class))) {
      AccountId id = newId();
      provisioning().insertIfAbsent(id, capabilities);

      Account stored = lookup().findById(id).orElseThrow();

      assertThat(stored.id()).isEqualTo(id);
      assertThat(stored.capabilities()).containsExactlyInAnyOrderElementsOf(capabilities);
    }
  }

  @Test
  void aNewAccountIsActive() {
    AccountId id = newId();
    provisioning().insertIfAbsent(id, EnumSet.of(Capability.CANDIDATE));

    assertThat(lookup().findById(id).orElseThrow().deletionStatus())
        .isEqualTo(DeletionStatus.ACTIVE);
  }

  @Test
  void bothPortsSeeTheSameAccount() {
    AccountId id = newId();
    provisioning().insertIfAbsent(id, EnumSet.allOf(Capability.class));

    assertThat(provisioning().findById(id)).isEqualTo(lookup().findById(id)).isPresent();
  }

  @Test
  void accountsDoNotInterfereWithEachOther() {
    AccountId candidate = newId();
    AccountId recruiter = newId();

    provisioning().insertIfAbsent(candidate, EnumSet.of(Capability.CANDIDATE));
    provisioning().insertIfAbsent(recruiter, EnumSet.of(Capability.RECRUITER));

    assertThat(lookup().findById(candidate).orElseThrow().capabilities())
        .containsExactly(Capability.CANDIDATE);
    assertThat(lookup().findById(recruiter).orElseThrow().capabilities())
        .containsExactly(Capability.RECRUITER);
  }

  @Test
  void anAccountWithoutAnyCapabilityCanNotBeCreated() {
    // BR-ACCT-01: toda cuenta tiene al menos una capacidad. Cada tecnología lo rechaza a su manera.
    AccountId id = newId();
    AccountProvisioningPort port = provisioning();
    Set<Capability> none = EnumSet.noneOf(Capability.class);

    assertThatThrownBy(() -> port.insertIfAbsent(id, none)).isInstanceOf(RuntimeException.class);
    assertThat(lookup().findById(id)).isEmpty();
  }

  @Test
  void simultaneousFirstLoginsOfTheSameIdentityCreateOneAccountAndNoneFails() throws Exception {
    // ADR-0020: dos primeros logins a la vez no pueden fallar ni duplicar la cuenta.
    int logins = 16;
    AccountId id = newId();
    CountDownLatch startTogether = new CountDownLatch(1);
    ExecutorService pool = Executors.newFixedThreadPool(logins);
    try {
      List<Future<Boolean>> results = new ArrayList<>();
      for (int i = 0; i < logins; i++) {
        results.add(
            pool.submit(
                () -> {
                  startTogether.await();
                  return provisioning().insertIfAbsent(id, EnumSet.of(Capability.CANDIDATE));
                }));
      }
      startTogether.countDown();

      long created = 0;
      for (Future<Boolean> result : results) {
        if (result.get(30, TimeUnit.SECONDS)) {
          created++;
        }
      }
      assertThat(created).as("solo un login crea la cuenta").isEqualTo(1);
    } finally {
      pool.shutdownNow();
    }
    assertThat(lookup().findById(id)).isPresent();
  }
}
