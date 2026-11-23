package com.usac.jobseeker.cv.application.usecase.saveprofessionalprofile;

import static org.assertj.core.api.Assertions.assertThat;

import com.usac.jobseeker.common.domain.model.AccountId;
import com.usac.jobseeker.cv.domain.model.ProfessionalIdentity;
import com.usac.jobseeker.cv.domain.model.ProfessionalProfile;
import com.usac.jobseeker.cv.domain.model.ProfessionalProfileId;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Lo que debe cumplir cualquier almacén de perfiles. Lo ejecutan la versión en memoria de las
 * pruebas de los casos de uso y el adaptador JDBC contra Postgres real, para que el doble no se
 * desvíe del comportamiento verdadero.
 */
public abstract class ProfessionalProfileStoreContract {

  private static final Instant T0 = Instant.parse("2026-11-23T10:00:00Z");

  protected abstract ProfessionalProfileStorePort store();

  /** Prepara lo que la implementación necesite para que la cuenta exista (p. ej. su fila). */
  protected abstract void accountExists(AccountId account);

  private ProfessionalProfile newProfile(AccountId account, String name) {
    return ProfessionalProfile.create(
        new ProfessionalProfileId(UUID.randomUUID()),
        account,
        ProfessionalIdentity.of(name, "Titular", "Línea 1\nLínea 2"),
        T0);
  }

  private AccountId newAccount() {
    AccountId account = new AccountId(UUID.randomUUID());
    accountExists(account);
    return account;
  }

  @Test
  void anAccountWithoutProfileHasNone() {
    assertThat(store().findByAccountId(newAccount())).isEmpty();
  }

  @Test
  void aCreatedProfileIsReadBackWithEveryField() {
    AccountId account = newAccount();
    ProfessionalProfile profile = newProfile(account, "Ana");

    assertThat(store().insertIfAbsent(profile)).isTrue();

    assertThat(store().findByAccountId(account)).contains(profile);
  }

  @Test
  void aSecondCreationForTheSameAccountDoesNothing() {
    AccountId account = newAccount();
    ProfessionalProfile first = newProfile(account, "Ana");
    store().insertIfAbsent(first);

    assertThat(store().insertIfAbsent(newProfile(account, "Otra"))).isFalse();

    assertThat(store().findByAccountId(account)).contains(first);
  }

  @Test
  void anUpdateWithTheStoredVersionWritesTheNewOne() {
    AccountId account = newAccount();
    ProfessionalProfile v1 = newProfile(account, "Ana");
    store().insertIfAbsent(v1);
    ProfessionalProfile v2 =
        v1.edit(ProfessionalIdentity.of("Ana López", null, null), T0.plusSeconds(60));

    assertThat(store().updateIfVersion(v2, 1)).isTrue();

    assertThat(store().findByAccountId(account)).contains(v2);
  }

  @Test
  void anUpdateWithAStaleVersionWritesNothing() {
    AccountId account = newAccount();
    ProfessionalProfile v1 = newProfile(account, "Ana");
    store().insertIfAbsent(v1);
    ProfessionalProfile v2 =
        v1.edit(ProfessionalIdentity.of("Ana López", null, null), T0.plusSeconds(60));
    store().updateIfVersion(v2, 1);
    ProfessionalProfile loser =
        v1.edit(ProfessionalIdentity.of("Perdedor", null, null), T0.plusSeconds(90));

    assertThat(store().updateIfVersion(loser, 1)).isFalse();

    assertThat(store().findByAccountId(account)).contains(v2);
  }

  @Test
  void anUpdateOfAnAccountWithoutProfileWritesNothing() {
    AccountId account = newAccount();

    assertThat(store().updateIfVersion(newProfile(account, "Ana"), 0)).isFalse();

    assertThat(store().findByAccountId(account)).isEmpty();
  }

  @Test
  void theProfilesOfDifferentAccountsAreIndependent() {
    AccountId one = newAccount();
    AccountId other = newAccount();
    store().insertIfAbsent(newProfile(one, "Uno"));
    store().insertIfAbsent(newProfile(other, "Otro"));

    assertThat(store().findByAccountId(one))
        .get()
        .extracting(p -> p.identity().fullName())
        .isEqualTo("Uno");
    assertThat(store().findByAccountId(other))
        .get()
        .extracting(p -> p.identity().fullName())
        .isEqualTo("Otro");
  }
}
