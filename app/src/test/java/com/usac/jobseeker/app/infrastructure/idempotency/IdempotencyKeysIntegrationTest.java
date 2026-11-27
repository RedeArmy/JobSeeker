package com.usac.jobseeker.app.infrastructure.idempotency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.usac.jobseeker.app.InfrastructureTestConfiguration;
import com.usac.jobseeker.common.domain.error.IdempotencyKeyReusedException;
import com.usac.jobseeker.common.domain.model.AccountId;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Las claves de idempotencia contra Postgres real (TD-010): la reserva, la respuesta guardada, la
 * clave reutilizada, la espera entre dos peticiones simultáneas y la purga. Con las anotaciones del
 * grupo principal de pruebas reutiliza su contexto (TD-017).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(InfrastructureTestConfiguration.class)
class IdempotencyKeysIntegrationTest {

  private static final String HASH = "a".repeat(64);

  @Autowired private JdbcIdempotencyKeys keys;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private PlatformTransactionManager transactions;

  private final AccountId account = new AccountId(UUID.randomUUID());
  private final ExecutorService pool = Executors.newFixedThreadPool(2);

  @AfterEach
  void stop() {
    pool.shutdownNow();
  }

  @Test
  void aNewKeyIsClaimedAndThenAnswersWithWhatWasStored() {
    assertThat(keys.claim(account, "k-1", HASH)).isEmpty();
    keys.complete(account, "k-1", "respuesta \"con\" comillas");

    assertThat(keys.claim(account, "k-1", HASH)).contains("respuesta \"con\" comillas");
    assertThat(keys.claim(new AccountId(UUID.randomUUID()), "k-1", HASH))
        .as("la clave es por cuenta")
        .isEmpty();
  }

  @Test
  void theSameKeyWithAnotherRequestIsAnError() {
    keys.claim(account, "k-1", HASH);
    keys.complete(account, "k-1", "x");

    String otherRequest = "b".repeat(64);

    assertThatThrownBy(() -> keys.claim(account, "k-1", otherRequest))
        .isInstanceOf(IdempotencyKeyReusedException.class);
  }

  // La segunda petición espera a que la primera confirme y recibe su respuesta: el efecto no se
  // repite. Si la primera se revierte, la segunda reserva la clave y lo hace ella.
  @Test
  void aSimultaneousRequestWaitsForTheFirstAndGetsItsAnswer() throws Exception {
    TransactionTemplate tx = new TransactionTemplate(transactions);
    CountDownLatch claimed = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    Future<?> first =
        pool.submit(
            () ->
                tx.executeWithoutResult(
                    status -> {
                      keys.claim(account, "k-1", HASH);
                      claimed.countDown();
                      await(release);
                      keys.complete(account, "k-1", "la primera");
                    }));
    await(claimed);
    Future<Optional<String>> second =
        pool.submit(() -> tx.execute(status -> keys.claim(account, "k-1", HASH)));

    assertThatThrownBy(() -> second.get(300, TimeUnit.MILLISECONDS))
        .as("espera a la primera")
        .isInstanceOf(TimeoutException.class);
    release.countDown();
    first.get(10, TimeUnit.SECONDS);

    assertThat(second.get(10, TimeUnit.SECONDS)).contains("la primera");
  }

  @Test
  void aRolledBackClaimLeavesTheKeyFree() {
    TransactionTemplate tx = new TransactionTemplate(transactions);
    tx.executeWithoutResult(
        status -> {
          keys.claim(account, "k-1", HASH);
          status.setRollbackOnly();
        });

    assertThat(keys.claim(account, "k-1", HASH)).isEmpty();
  }

  @Test
  void onlyKeysOlderThanTheRetentionArePurged() {
    Instant now = Instant.parse("2027-01-10T10:00:00Z");
    UUID owner = UUID.randomUUID();
    insertAt(owner, "vieja", now.minus(Duration.ofHours(25)));
    insertAt(owner, "reciente", now.minus(Duration.ofHours(1)));
    IdempotencyRetentionJob job =
        new IdempotencyRetentionJob(
            keys,
            new IdempotencyProperties(Duration.ofHours(24), 1, 10),
            new SimpleMeterRegistry(),
            Clock.fixed(now, ZoneOffset.UTC));

    job.purge();

    assertThat(
            jdbc.queryForList(
                "select key from idempotency_keys where account_id = ?", String.class, owner))
        .containsExactly("reciente");
  }

  @Test
  void theRetentionCannotBeShorterThanAnHour() {
    Duration tooShort = Duration.ofMinutes(30);
    assertThatThrownBy(() -> new IdempotencyProperties(tooShort, 1, 1))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private void insertAt(UUID owner, String key, Instant at) {
    jdbc.update(
        "insert into idempotency_keys (account_id, key, request_hash, created_at)"
            + " values (?, ?, ?, ?)",
        owner,
        key,
        HASH,
        OffsetDateTime.ofInstant(at, ZoneOffset.UTC));
  }

  private static void await(CountDownLatch latch) {
    try {
      latch.await(10, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
  }
}
