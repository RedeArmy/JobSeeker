package com.usac.jobseeker.app.infrastructure.security.liveness;

import static org.assertj.core.api.Assertions.assertThat;

import com.usac.jobseeker.app.InfrastructureTestConfiguration;
import com.usac.jobseeker.app.infrastructure.security.liveness.LivenessCoordinator.Verdict;
import com.usac.jobseeker.app.infrastructure.security.liveness.RefreshTokenClient.Result;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * La prueba de vida contra un Redis real y un Clavaris falso con refresh tokens de **un solo uso**,
 * como los de Clavaris: reutilizar uno es `invalid_grant`. Lo que se prueba es lo que el ADR-0020
 * midió contra Clavaris real: cerrojo, relectura dentro de él, clasificación, margen y caché
 * negativa.
 */
@SpringBootTest
@Import(InfrastructureTestConfiguration.class)
class LivenessCoordinatorIntegrationTest {

  private static final Duration INTERVAL = Duration.ofMinutes(10);
  private static final Duration MARGIN = Duration.ofHours(2);

  @Autowired private StringRedisTemplate redis;
  @Autowired private RefreshTokenCipher cipher;

  private MutableClock clock;
  private LivenessStore store;
  private SingleUseClavaris clavaris;

  @BeforeEach
  void setUp() {
    clock = new MutableClock(Instant.parse("2026-11-21T12:00:00Z"));
    store = new LivenessStore(redis, cipher, Duration.ofHours(8));
    clavaris = new SingleUseClavaris();
  }

  private LivenessCoordinator coordinator(boolean failOpen, Duration lockWait) {
    LivenessProperties properties =
        new LivenessProperties(
            INTERVAL,
            MARGIN,
            failOpen,
            false,
            Duration.ofSeconds(45),
            Duration.ofSeconds(30),
            lockWait,
            Duration.ofSeconds(2),
            Duration.ofSeconds(5));
    return new LivenessCoordinator(store, clavaris, properties, MARGIN, clock, () -> null);
  }

  private String login() {
    return store.begin(clavaris.issue(), clock.instant());
  }

  @Test
  void aSessionInsideTheIntervalIsAliveWithoutCallingClavaris() {
    String link = login();
    clock.advance(Duration.ofMinutes(9));

    assertThat(coordinator(true, Duration.ofSeconds(2)).verify(link)).isEqualTo(Verdict.ALIVE);
    assertThat(clavaris.calls.get()).isZero();
  }

  @Test
  void aDueRenewalRotatesTheStoredRefreshToken() {
    String link = login();
    String firstToken = clavaris.valid;
    clock.advance(INTERVAL);

    assertThat(coordinator(true, Duration.ofSeconds(2)).verify(link)).isEqualTo(Verdict.ALIVE);

    assertThat(clavaris.calls.get()).isEqualTo(1);
    LivenessState state = store.find(link).orElseThrow();
    assertThat(store.refreshTokenOf(link, state))
        .isEqualTo(clavaris.valid)
        .isNotEqualTo(firstToken);
    assertThat(state.lastRenewed()).isEqualTo(clock.instant());
    // Segunda comprobación dentro del intervalo: no vuelve a llamar.
    assertThat(coordinator(true, Duration.ofSeconds(2)).verify(link)).isEqualTo(Verdict.ALIVE);
    assertThat(clavaris.calls.get()).isEqualTo(1);
  }

  @Test
  void invalidGrantClosesTheSessionAndDeletesTheState() {
    String link = login();
    clavaris.next = token -> new Result.InvalidGrant();
    clock.advance(INTERVAL);

    assertThat(coordinator(true, Duration.ofSeconds(2)).verify(link)).isEqualTo(Verdict.CLOSED);
    assertThat(store.find(link)).isEmpty();
  }

  @Test
  void aTransientFailureKeepsTheSessionAndPausesRetriesForTheNegativeCacheWindow() {
    String link = login();
    clavaris.next = token -> new Result.Transient("red_o_tiempo_agotado");
    clock.advance(INTERVAL);
    LivenessCoordinator coordinator = coordinator(true, Duration.ofSeconds(2));

    assertThat(coordinator.verify(link)).isEqualTo(Verdict.ALIVE);
    assertThat(clavaris.calls.get()).isEqualTo(1);

    clock.advance(Duration.ofSeconds(30)); // dentro de los 45 s de caché negativa
    assertThat(coordinator.verify(link)).isEqualTo(Verdict.ALIVE);
    assertThat(clavaris.calls.get()).isEqualTo(1);

    clock.advance(Duration.ofSeconds(20)); // pasada la pausa vuelve a intentar
    assertThat(coordinator.verify(link)).isEqualTo(Verdict.ALIVE);
    assertThat(clavaris.calls.get()).isEqualTo(2);
  }

  @Test
  void aSessionWithoutASuccessfulRenewalIsClosedOnceTheMarginIsExceeded() {
    String link = login();
    clavaris.next = token -> new Result.Transient("http_503");
    LivenessCoordinator coordinator = coordinator(true, Duration.ofSeconds(2));

    clock.advance(Duration.ofMinutes(119));
    assertThat(coordinator.verify(link)).isEqualTo(Verdict.ALIVE);
    clock.advance(Duration.ofMinutes(2));
    assertThat(coordinator.verify(link)).isEqualTo(Verdict.CLOSED);
    assertThat(store.find(link)).isEmpty();
  }

  @Test
  void theFailOpenSwitchOffClosesTheSessionOnTheFirstTransientFailure() {
    String link = login();
    clavaris.next = token -> new Result.Transient("http_503");
    clock.advance(INTERVAL);

    assertThat(coordinator(false, Duration.ofSeconds(2)).verify(link)).isEqualTo(Verdict.CLOSED);
  }

  @Test
  void aSessionWithNoStateIsClosed() {
    assertThat(coordinator(true, Duration.ofSeconds(2)).verify("no-existe"))
        .isEqualTo(Verdict.CLOSED);
  }

  @Test
  void aTokenThatCannotBeDecryptedClosesTheSessionInsteadOfCallingClavaris() {
    String link = login();
    redis.opsForHash().put("jobseeker:liveness:" + link, "rt", "k9.valor-con-clave-retirada");
    clock.advance(INTERVAL);

    assertThat(coordinator(true, Duration.ofSeconds(2)).verify(link)).isEqualTo(Verdict.CLOSED);
    assertThat(clavaris.calls.get()).isZero();
  }

  @Test
  void eightSimultaneousRequestsRenewOnceAndAllSurvive() throws Exception {
    // El caso medido contra Clavaris real: sin cerrojo 7 de 8 fallaron con reuse_detected. Aquí el
    // cliente simulado revoca la familia ante cualquier reutilización, como Clavaris.
    String link = login();
    clock.advance(INTERVAL);
    clavaris.latencyMillis = 150;
    LivenessCoordinator coordinator = coordinator(true, Duration.ofSeconds(10));

    ExecutorService pool = Executors.newFixedThreadPool(8);
    try {
      List<Future<Verdict>> verdicts = new ArrayList<>();
      for (int i = 0; i < 8; i++) {
        verdicts.add(pool.submit(() -> coordinator.verify(link)));
      }
      for (Future<Verdict> verdict : verdicts) {
        assertThat(verdict.get()).isEqualTo(Verdict.ALIVE);
      }
    } finally {
      pool.shutdownNow();
    }

    assertThat(clavaris.calls.get()).as("una sola renovación").isEqualTo(1);
    assertThat(clavaris.familyRevoked).as("sin reutilización").isFalse();
  }

  @Test
  void aRequestThatCannotGetTheLockInTimeContinuesWithoutRenewing() {
    String link = login();
    clock.advance(INTERVAL);
    String owner = store.acquireLock(link, Duration.ofSeconds(30), Duration.ZERO).orElseThrow();

    assertThat(coordinator(true, Duration.ofMillis(200)).verify(link)).isEqualTo(Verdict.ALIVE);
    assertThat(clavaris.calls.get()).isZero();

    store.release(link, owner);
  }

  @Test
  void theLockIsReleasedEvenWhenTheRenewalFails() {
    String link = login();
    clavaris.next = token -> new Result.Transient("http_503");
    clock.advance(INTERVAL);
    coordinator(true, Duration.ofSeconds(2)).verify(link);

    assertThat(store.acquireLock(link, Duration.ofSeconds(30), Duration.ZERO)).isPresent();
  }

  @Test
  void aLockCanOnlyBeReleasedByItsOwner() {
    String link = login();
    String owner = store.acquireLock(link, Duration.ofSeconds(30), Duration.ZERO).orElseThrow();

    store.release(link, "otro-dueño");

    assertThat(store.acquireLock(link, Duration.ofSeconds(30), Duration.ZERO)).isEmpty();
    store.release(link, owner);
    assertThat(store.acquireLock(link, Duration.ofSeconds(30), Duration.ZERO)).isPresent();
  }

  @Test
  void theRefreshTokenIsEncryptedAtRestAndTheRecordExpires() {
    String link = login();
    Map<Object, Object> raw = redis.opsForHash().entries("jobseeker:liveness:" + link);

    assertThat(raw.get("rt").toString()).doesNotContain(clavaris.valid).startsWith("test.");
    assertThat(redis.getExpire("jobseeker:liveness:" + link)).isBetween(1L, 8L * 3600);
  }

  /** Clavaris falso con refresh tokens de un solo uso: reutilizar uno revoca la familia. */
  private static final class SingleUseClavaris implements RefreshTokenClient {
    final AtomicInteger calls = new AtomicInteger();
    final AtomicInteger issued = new AtomicInteger();
    volatile String valid;
    volatile boolean familyRevoked;
    volatile long latencyMillis;
    volatile Function<String, Result> next;

    // Simula la latencia de Clavaris esperando un cierre que nunca llega: caduca por tiempo.
    private static void pause(long millis) {
      try {
        new CountDownLatch(1).await(millis, TimeUnit.MILLISECONDS);
      } catch (InterruptedException _) {
        Thread.currentThread().interrupt();
      }
    }

    String issue() {
      valid = "refresh-token-secreto-" + issued.incrementAndGet();
      return valid;
    }

    @Override
    public synchronized Result refresh(String refreshToken) {
      calls.incrementAndGet();
      if (latencyMillis > 0) {
        pause(latencyMillis);
      }
      if (next != null) {
        return next.apply(refreshToken);
      }
      if (familyRevoked || !refreshToken.equals(valid)) {
        familyRevoked = true;
        return new Result.InvalidGrant();
      }
      return new Result.Renewed(issue());
    }

    @Override
    public boolean revoke(String refreshToken) {
      return true;
    }
  }

  private static final class MutableClock extends Clock {
    private volatile Instant now;

    MutableClock(Instant start) {
      this.now = start;
    }

    void advance(Duration by) {
      now = now.plus(by);
    }

    @Override
    public ZoneOffset getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(java.time.ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return now;
    }
  }
}
