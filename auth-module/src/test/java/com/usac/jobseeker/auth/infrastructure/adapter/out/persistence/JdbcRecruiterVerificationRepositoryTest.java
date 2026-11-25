package com.usac.jobseeker.auth.infrastructure.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.usac.jobseeker.auth.infrastructure.AuthPostgres;
import com.usac.jobseeker.common.domain.model.AccountId;
import com.usac.jobseeker.common.domain.model.RecruiterVerification;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * La verificación del reclutador contra la tabla real que crea la migración (Postgres 17): el valor
 * inicial seguro, quién cuenta como reclutador activo, la escritura condicional y la restricción
 * que la base repite como última defensa.
 */
class JdbcRecruiterVerificationRepositoryTest {

  private final List<AccountId> changed = new ArrayList<>();
  private final JdbcClient jdbc = JdbcClient.create(AuthPostgres.dataSource());
  private final JdbcRecruiterVerificationRepository repository =
      new JdbcRecruiterVerificationRepository(jdbc, changed::add);

  private AccountId account(boolean recruiter, String deletionStatus, String identityStatus) {
    UUID id = UUID.randomUUID();
    jdbc.sql(
            "insert into accounts (id, is_candidate_enabled, is_recruiter_enabled, deletion_status,"
                + " identity_status) values (:id, true, :recruiter, :deletion, :identity)")
        .param("id", id)
        .param("recruiter", recruiter)
        .param("deletion", deletionStatus)
        .param("identity", identityStatus)
        .update();
    return new AccountId(id);
  }

  // Verificar es una decisión que alguien toma, nunca un efecto de crear la cuenta.
  @Test
  void aNewRecruiterIsUnverified() {
    AccountId recruiter = account(true, "ACTIVE", "ACTIVE");

    assertThat(repository.findOfActiveRecruiter(recruiter))
        .contains(RecruiterVerification.UNVERIFIED);
  }

  @Test
  void anAccountThatIsNotARecruiterHasNone() {
    AccountId candidate = account(false, "ACTIVE", "ACTIVE");

    assertThat(repository.findOfActiveRecruiter(candidate)).isEmpty();
    assertThat(repository.findOfRecruiter(candidate)).isEmpty();
    assertThat(repository.findOfActiveRecruiter(new AccountId(UUID.randomUUID()))).isEmpty();
  }

  // Una cuenta que no puede actuar no recibe confianza, aunque conserve la capacidad.
  @Test
  void anAccountBeingDeletedOrWithADeletedIdentityIsNotAnActiveRecruiter() {
    AccountId pending = account(true, "PENDING_DELETION", "ACTIVE");
    AccountId deletedIdentity = account(true, "ACTIVE", "DELETED");

    assertThat(repository.findOfActiveRecruiter(pending)).isEmpty();
    assertThat(repository.findOfActiveRecruiter(deletedIdentity)).isEmpty();
    // Quien verifica sí la ve, para poder suspenderla.
    assertThat(repository.findOfRecruiter(pending)).contains(RecruiterVerification.UNVERIFIED);
  }

  @Test
  void theUpdateOnlyWinsFromTheExpectedValue() {
    AccountId recruiter = account(true, "ACTIVE", "ACTIVE");

    assertThat(
            repository.updateIfCurrent(
                recruiter, RecruiterVerification.UNVERIFIED, RecruiterVerification.VERIFIED))
        .isTrue();
    assertThat(
            repository.updateIfCurrent(
                recruiter, RecruiterVerification.UNVERIFIED, RecruiterVerification.SUSPENDED))
        .isFalse();
    assertThat(repository.findOfActiveRecruiter(recruiter))
        .contains(RecruiterVerification.VERIFIED);
  }

  // La resolución de actor recuerda la cuenta por petición: todo escritor de `accounts` debe
  // avisar.
  @Test
  void aSuccessfulUpdateNotifiesTheObserverAndALostRaceDoesNot() {
    AccountId recruiter = account(true, "ACTIVE", "ACTIVE");

    repository.updateIfCurrent(
        recruiter, RecruiterVerification.VERIFIED, RecruiterVerification.SUSPENDED);
    assertThat(changed).isEmpty();

    repository.updateIfCurrent(
        recruiter, RecruiterVerification.UNVERIFIED, RecruiterVerification.VERIFIED);
    assertThat(changed).containsExactly(recruiter);
  }

  @Test
  void aCandidateOnlyAccountCannotBeVerified() {
    AccountId candidate = account(false, "ACTIVE", "ACTIVE");

    assertThat(
            repository.updateIfCurrent(
                candidate, RecruiterVerification.UNVERIFIED, RecruiterVerification.VERIFIED))
        .isFalse();
  }

  @Test
  void theDatabaseRejectsAnUnknownValue() {
    AccountId recruiter = account(true, "ACTIVE", "ACTIVE");

    assertThatThrownBy(
            () ->
                jdbc.sql("update accounts set recruiter_verification = 'TRUSTED' where id = :id")
                    .param("id", recruiter.value())
                    .update())
        .isInstanceOf(DataIntegrityViolationException.class);
  }
}
