package com.usac.jobseeker.cv.infrastructure.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.usac.jobseeker.common.domain.model.AccountId;
import com.usac.jobseeker.cv.application.usecase.saveprofessionalprofile.ProfessionalProfileStoreContract;
import com.usac.jobseeker.cv.application.usecase.saveprofessionalprofile.ProfessionalProfileStorePort;
import com.usac.jobseeker.cv.domain.model.ProfessionalIdentity;
import com.usac.jobseeker.cv.domain.model.ProfessionalProfile;
import com.usac.jobseeker.cv.domain.model.ProfessionalProfileId;
import com.usac.jobseeker.cv.infrastructure.CvPostgres;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * El adaptador JDBC cumple el mismo contrato que el almacén en memoria, contra la tabla real que
 * crea la migración del propio módulo en Postgres 17 (ADR-0028), y además las restricciones y la
 * concurrencia que solo una base real puede demostrar.
 */
class JdbcProfessionalProfileRepositoryContractTest extends ProfessionalProfileStoreContract {

  private final JdbcClient jdbc = JdbcClient.create(CvPostgres.dataSource());
  private final JdbcProfessionalProfileRepository repository =
      new JdbcProfessionalProfileRepository(jdbc);

  @Override
  protected ProfessionalProfileStorePort store() {
    return repository;
  }

  @Override
  protected void accountExists(AccountId account) {
    jdbc.sql("insert into accounts (id, is_candidate_enabled) values (:id, true)")
        .param("id", account.value())
        .update();
  }

  private ProfessionalProfile profileOf(
      AccountId account, String name, String headline, String bio) {
    return ProfessionalProfile.create(
        new ProfessionalProfileId(UUID.randomUUID()),
        account,
        new ProfessionalIdentity(name, headline, bio),
        Instant.parse("2026-11-23T10:00:00Z"));
  }

  @Test
  void aProfileOfAnAccountThatDoesNotExistIsRejectedByTheForeignKey() {
    var orphan = profileOf(new AccountId(UUID.randomUUID()), "Ana", null, null);

    assertThatThrownBy(() -> repository.insertIfAbsent(orphan))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void theDatabaseRefusesWhatTheDomainAlreadyRefuses() {
    AccountId account = new AccountId(UUID.randomUUID());
    accountExists(account);

    assertThatThrownBy(() -> repository.insertIfAbsent(profileOf(account, "   ", null, null)))
        .isInstanceOf(DataIntegrityViolationException.class);
    assertThatThrownBy(
            () -> repository.insertIfAbsent(profileOf(account, "n".repeat(201), null, null)))
        .isInstanceOf(DataIntegrityViolationException.class);
    assertThatThrownBy(
            () -> repository.insertIfAbsent(profileOf(account, "Ana", "h".repeat(201), null)))
        .isInstanceOf(DataIntegrityViolationException.class);
    assertThatThrownBy(
            () -> repository.insertIfAbsent(profileOf(account, "Ana", null, "b".repeat(4001))))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void theLimitsOfTheColumnsAreInclusive() {
    AccountId account = new AccountId(UUID.randomUUID());
    accountExists(account);

    assertThat(
            repository.insertIfAbsent(
                profileOf(account, "n".repeat(200), "h".repeat(200), "b".repeat(4000))))
        .isTrue();
  }

  @Test
  void optionalFieldsComeBackAsNullAndTextKeepsAccentsEmojiAndNewlines() {
    AccountId account = new AccountId(UUID.randomUUID());
    accountExists(account);
    var plain = profileOf(account, "Ana", null, null);
    repository.insertIfAbsent(plain);
    assertThat(repository.findByAccountId(account))
        .get()
        .extracting(p -> p.identity().headline())
        .isNull();

    AccountId other = new AccountId(UUID.randomUUID());
    accountExists(other);
    var rich = profileOf(other, "José Ñandú 😀", "Líder técnico", "Línea 1\nLínea 2\tcon tab");
    repository.insertIfAbsent(rich);
    assertThat(repository.findByAccountId(other)).contains(rich);
  }

  @Test
  void twoCreationsAtTheSameTimeLeaveExactlyOneProfileAndOneWinner() throws Exception {
    AccountId account = new AccountId(UUID.randomUUID());
    accountExists(account);
    assertThat(
            runConcurrently(
                2, () -> repository.insertIfAbsent(profileOf(account, "Ana", null, null))))
        .containsOnlyOnce(true);
    assertThat(
            jdbc.sql("select count(*) from professional_profiles where account_id = :a")
                .param("a", account.value())
                .query(Long.class)
                .single())
        .isEqualTo(1L);
  }

  @Test
  void twoEditsWithTheSameVersionAtTheSameTimeHaveExactlyOneWinner() throws Exception {
    AccountId account = new AccountId(UUID.randomUUID());
    accountExists(account);
    var v1 = profileOf(account, "Ana", null, null);
    repository.insertIfAbsent(v1);

    var results =
        runConcurrently(
            4,
            () ->
                repository.updateIfVersion(
                    v1.edit(
                        ProfessionalIdentity.of("Editor " + UUID.randomUUID(), null, null),
                        Instant.parse("2026-11-23T11:00:00Z")),
                    1));

    assertThat(results.stream().filter(Boolean::booleanValue)).hasSize(1);
    assertThat(repository.findByAccountId(account))
        .get()
        .extracting(ProfessionalProfile::version)
        .isEqualTo(2L);
  }

  private static java.util.List<Boolean> runConcurrently(int threads, Callable<Boolean> task)
      throws Exception {
    ExecutorService pool = Executors.newFixedThreadPool(threads);
    try {
      CountDownLatch go = new CountDownLatch(1);
      java.util.List<Future<Boolean>> futures = new java.util.ArrayList<>();
      for (int i = 0; i < threads; i++) {
        futures.add(
            pool.submit(
                () -> {
                  go.await();
                  return task.call();
                }));
      }
      go.countDown();
      java.util.List<Boolean> results = new java.util.ArrayList<>();
      for (Future<Boolean> future : futures) {
        results.add(future.get());
      }
      return results;
    } finally {
      pool.shutdownNow();
    }
  }
}
