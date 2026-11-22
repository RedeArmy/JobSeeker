package com.usac.jobseeker.app.infrastructure.security.liveness;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * El estado de la prueba de vida y su cerrojo contra un Redis real (ADR-0020, reglas 3, 5, 6 y 7):
 * el token solo existe cifrado, el registro caduca con la sesión y el cerrojo no se roba.
 */
class LivenessStoreTest {

  private static final Instant NOW = Instant.parse("2026-11-22T12:00:00Z");
  private static final Duration TTL = Duration.ofSeconds(100);

  private final StringRedisTemplate redis = SharedRedis.template();
  private final RefreshTokenCipher cipher =
      RefreshTokenCipher.from(
          new EncryptionProperties("k1", "k1:" + Base64.getEncoder().encodeToString(new byte[32])));
  private final LivenessStore store = new LivenessStore(redis, cipher, TTL);

  @Test
  void aStoredTokenCanBeReadBackDecryptedWithItsRenewalTime() {
    String link = store.begin("refresh-token-uno", NOW);

    LivenessState state = store.find(link).orElseThrow();

    assertThat(store.refreshTokenOf(link, state)).isEqualTo("refresh-token-uno");
    assertThat(state.lastRenewed()).isEqualTo(NOW);
    assertThat(state.retryNotBefore()).isEqualTo(Instant.EPOCH);
  }

  @Test
  void theTokenIsNeverInRedisInThePlain() {
    String link = store.begin("refresh-token-secreto", NOW);

    assertThat(redis.opsForHash().entries("jobseeker:liveness:" + link).toString())
        .doesNotContain("refresh-token-secreto");
  }

  @Test
  void theKeysLiveInTheirOwnNamespacesApartFromScoresAndRateLimits() {
    String link = store.begin("t", NOW);
    store.acquireLock(link, Duration.ofSeconds(5), Duration.ZERO);

    assertThat(redis.hasKey("jobseeker:liveness:" + link)).isTrue();
    assertThat(redis.hasKey("jobseeker:liveness-lock:" + link)).isTrue();
  }

  @Test
  void anUnknownLinkHasNoState() {
    assertThat(store.find("no-existe")).isEmpty();
  }

  @Test
  void aRecordWithoutItsTokenCountsAsAbsentInsteadOfFailing() {
    redis.opsForHash().put("jobseeker:liveness:huerfano", "renewed", "1");

    assertThat(store.find("huerfano")).isEmpty();
  }

  @Test
  void everyLinkIsUniqueAndLongEnoughToBeUnguessable() {
    Set<String> links = new HashSet<>();
    for (int i = 0; i < 500; i++) {
      links.add(store.begin("t", NOW));
    }

    assertThat(links)
        .hasSize(500)
        .allSatisfy(link -> assertThat(link).matches("[A-Za-z0-9_-]{22}"));
  }

  @Test
  void renewalStoresTheSuccessorAndForgetsAnyPauseAfterAFailure() {
    String link = store.begin("viejo", NOW);
    LivenessState before = store.find(link).orElseThrow();
    store.recordTransientFailure(link, before, NOW.plusSeconds(45));
    LivenessState paused = store.find(link).orElseThrow();

    store.recordRenewed(link, "sucesor", paused, NOW.plusSeconds(600));

    LivenessState after = store.find(link).orElseThrow();
    assertThat(store.refreshTokenOf(link, after)).isEqualTo("sucesor");
    assertThat(after.lastRenewed()).isEqualTo(NOW.plusSeconds(600));
    assertThat(after.retryNotBefore()).isEqualTo(Instant.EPOCH);
  }

  @Test
  void renewalWithoutASuccessorKeepsTheCurrentToken() {
    String link = store.begin("el-mismo", NOW);
    LivenessState before = store.find(link).orElseThrow();

    store.recordRenewed(link, null, before, NOW.plusSeconds(600));

    LivenessState after = store.find(link).orElseThrow();
    assertThat(store.refreshTokenOf(link, after)).isEqualTo("el-mismo");
    assertThat(after.lastRenewed()).isEqualTo(NOW.plusSeconds(600));
  }

  @Test
  void aTransientFailureKeepsTheTokenAndTheLastGoodRenewalAndOnlySetsThePause() {
    String link = store.begin("conservado", NOW);
    LivenessState before = store.find(link).orElseThrow();

    store.recordTransientFailure(link, before, NOW.plusSeconds(45));

    LivenessState after = store.find(link).orElseThrow();
    assertThat(store.refreshTokenOf(link, after)).isEqualTo("conservado");
    assertThat(after.lastRenewed())
        .as("el margen se cuenta desde la última renovación buena")
        .isEqualTo(NOW);
    assertThat(after.retryNotBefore()).isEqualTo(NOW.plusSeconds(45));
  }

  @Test
  void deletingRemovesTheStateAndNothingElse() {
    String kept = store.begin("a", NOW);
    String removed = store.begin("b", NOW);

    store.delete(removed);

    assertThat(store.find(removed)).isEmpty();
    assertThat(store.find(kept)).isPresent();
  }

  @Test
  void theRecordExpiresWithTheSessionAndEveryWriteRenewsThatLimit() {
    String link = store.begin("t", NOW);
    assertThat(redis.getExpire("jobseeker:liveness:" + link)).isBetween(90L, 100L);

    redis.expire("jobseeker:liveness:" + link, Duration.ofSeconds(5));
    store.recordRenewed(link, null, store.find(link).orElseThrow(), NOW);

    assertThat(redis.getExpire("jobseeker:liveness:" + link)).isBetween(90L, 100L);
  }

  @Test
  void onlyOneHolderGetsTheLockAtATime() {
    String link = store.begin("t", NOW);

    Optional<String> first = store.acquireLock(link, Duration.ofSeconds(30), Duration.ZERO);
    Optional<String> second = store.acquireLock(link, Duration.ofSeconds(30), Duration.ZERO);

    assertThat(first).isPresent();
    assertThat(second).isEmpty();
  }

  @Test
  void aWaiterGivesUpOnlyAfterItsWaitHasElapsed() {
    String link = store.begin("t", NOW);
    store.acquireLock(link, Duration.ofSeconds(30), Duration.ZERO);

    long started = System.nanoTime();
    Optional<String> waited =
        store.acquireLock(link, Duration.ofSeconds(30), Duration.ofMillis(400));

    assertThat(waited).isEmpty();
    assertThat(Duration.ofNanos(System.nanoTime() - started))
        .isGreaterThanOrEqualTo(Duration.ofMillis(350));
  }

  @Test
  void aWaiterGetsTheLockAsSoonAsItsHolderReleasesIt() throws Exception {
    String link = store.begin("t", NOW);
    String holder = store.acquireLock(link, Duration.ofSeconds(30), Duration.ZERO).orElseThrow();
    Thread releaser =
        Thread.ofVirtual()
            .start(
                () -> {
                  pause(200);
                  store.release(link, holder);
                });

    Optional<String> waited =
        store.acquireLock(link, Duration.ofSeconds(30), Duration.ofSeconds(5));

    releaser.join();
    assertThat(waited).isPresent();
  }

  @Test
  void aLockCannotBeReleasedByAnyoneButItsHolder() {
    String link = store.begin("t", NOW);
    String holder = store.acquireLock(link, Duration.ofSeconds(30), Duration.ZERO).orElseThrow();

    store.release(link, "otro");
    assertThat(store.acquireLock(link, Duration.ofSeconds(30), Duration.ZERO)).isEmpty();

    store.release(link, holder);
    assertThat(store.acquireLock(link, Duration.ofSeconds(30), Duration.ZERO)).isPresent();
  }

  @Test
  void aLockExpiresByItselfSoACrashedHolderDoesNotBlockTheSessionForever() {
    String link = store.begin("t", NOW);
    store.acquireLock(link, Duration.ofMillis(300), Duration.ZERO);

    Optional<String> afterExpiry =
        store.acquireLock(link, Duration.ofSeconds(30), Duration.ofSeconds(5));

    assertThat(afterExpiry).isPresent();
  }

  @Test
  void locksOfDifferentSessionsDoNotInterfere() {
    String one = store.begin("t", NOW);
    String other = store.begin("t", NOW);

    store.acquireLock(one, Duration.ofSeconds(30), Duration.ZERO);

    assertThat(store.acquireLock(other, Duration.ofSeconds(30), Duration.ZERO)).isPresent();
  }

  // Una espera real y breve: el hilo que libera debe hacerlo mientras el otro ya está esperando.
  private static void pause(long millis) {
    try {
      new CountDownLatch(1).await(millis, TimeUnit.MILLISECONDS);
    } catch (InterruptedException _) {
      Thread.currentThread().interrupt();
    }
  }
}
