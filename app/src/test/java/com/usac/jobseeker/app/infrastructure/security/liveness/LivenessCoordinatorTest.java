package com.usac.jobseeker.app.infrastructure.security.liveness;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.spy;

import com.usac.jobseeker.app.infrastructure.security.liveness.LivenessCoordinator.Verdict;
import com.usac.jobseeker.app.infrastructure.security.liveness.RefreshTokenClient.Result;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * La prueba de vida contra un Redis real y un Clavaris falso con refresh tokens de **un solo uso**,
 * como los de Clavaris: reutilizar uno es `invalid_grant`. Lo que se prueba es lo que el ADR-0020
 * midió contra Clavaris real: cerrojo, relectura dentro de él, clasificación, margen y caché
 * negativa.
 *
 * <p>No levanta Spring: el coordinador es una clase con constructor, y arrancar la aplicación solo
 * para obtener un cliente de Redis haría la prueba lenta y la acoplaría a la configuración.
 */
class LivenessCoordinatorTest {

  private static final Duration INTERVAL = Duration.ofMinutes(10);
  private static final Duration MARGIN = Duration.ofHours(2);

  private final StringRedisTemplate redis = RedisForTests.template();
  private final RefreshTokenCipher cipher =
      RefreshTokenCipher.from(
          new EncryptionProperties("k1", "k1:" + Base64.getEncoder().encodeToString(new byte[32])));

  private MutableClock clock;
  private LivenessStore store;
  private SingleUseClavaris clavaris;
  private final MeterRegistry meters = new SimpleMeterRegistry();

  @BeforeEach
  void setUp() {
    clock = new MutableClock(Instant.parse("2026-11-21T12:00:00Z"));
    store = new LivenessStore(redis, cipher, Duration.ofHours(8));
    clavaris = new SingleUseClavaris();
  }

  private LivenessCoordinator coordinator(boolean failOpen, Duration lockWait) {
    return coordinatorUsing(store, failOpen, lockWait);
  }

  private LivenessCoordinator coordinatorUsing(
      LivenessStore usedStore, boolean failOpen, Duration lockWait) {
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
    return new LivenessCoordinator(usedStore, clavaris, properties, MARGIN, clock, () -> meters);
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

  // ---- métricas: cada desenlace se cuenta con su propio nombre (ADR-0012) ----

  private double counted(String outcome) {
    var counter = meters.find("jobseeker_liveness_total").tag("outcome", outcome).counter();
    return counter == null ? 0 : counter.count();
  }

  @Test
  void aSuccessfulRenewalIsCountedAsRenewedAndNothingElse() {
    String link = login();
    clock.advance(INTERVAL);

    coordinator(true, Duration.ofSeconds(2)).verify(link);

    assertThat(counted("renovado")).isEqualTo(1);
    assertThat(counted("fallo_transitorio")).isZero();
  }

  @Test
  void aTransientFailureIsCountedAndSoIsEachClosingWithItsOwnReason() {
    String link = login();
    clavaris.next = token -> new Result.Transient("http_503");
    clock.advance(INTERVAL);
    coordinator(true, Duration.ofSeconds(2)).verify(link);
    assertThat(counted("fallo_transitorio")).isEqualTo(1);

    clavaris.next = token -> new Result.InvalidGrant();
    String revoked = login();
    clock.advance(INTERVAL);
    coordinator(true, Duration.ofSeconds(2)).verify(revoked);
    assertThat(counted("cerrada_invalid_grant")).isEqualTo(1);

    coordinator(true, Duration.ofSeconds(2)).verify("no-existe");
    assertThat(counted("cerrada_sin_estado")).isEqualTo(1);
  }

  @Test
  void theMarginCloseAndTheForcedCloseAreCountedSeparately() {
    clavaris.next = token -> new Result.Transient("http_503");
    String forced = login();
    clock.advance(INTERVAL);
    coordinator(false, Duration.ofSeconds(2)).verify(forced);
    assertThat(counted("cerrada_cierre_forzado")).isEqualTo(1);

    String late = login();
    clock.advance(MARGIN.plusSeconds(1));
    coordinator(true, Duration.ofSeconds(2)).verify(late);
    assertThat(counted("cerrada_margen_agotado")).isEqualTo(1);
  }

  @Test
  void anUndecipherableTokenIsCountedAndItsStateIsDeleted() {
    String link = login();
    redis.opsForHash().put("jobseeker:liveness:" + link, "rt", "k9.valor-con-clave-retirada");
    clock.advance(INTERVAL);

    coordinator(true, Duration.ofSeconds(2)).verify(link);

    assertThat(counted("cerrada_token_ilegible")).isEqualTo(1);
    assertThat(store.find(link)).isEmpty();
  }

  @Test
  void theForcedCloseAlsoDeletesTheStateSoTheSessionCannotComeBack() {
    String link = login();
    clavaris.next = token -> new Result.Transient("http_503");
    clock.advance(INTERVAL);

    coordinator(false, Duration.ofSeconds(2)).verify(link);

    assertThat(store.find(link)).isEmpty();
  }

  @Test
  void aRequestThatGivesUpWaitingForTheLockIsCounted() {
    String link = login();
    clock.advance(INTERVAL);
    String owner = store.acquireLock(link, Duration.ofSeconds(30), Duration.ZERO).orElseThrow();

    coordinator(true, Duration.ofMillis(150)).verify(link);

    assertThat(counted("lock_agotado")).isEqualTo(1);
    store.release(link, owner);
  }

  // ---- bordes exactos: una condición mal escrita (> en vez de >=) cambia el comportamiento ----

  @Test
  void theRenewalIsDueExactlyWhenTheIntervalHasElapsedAndNotASecondBefore() {
    String link = login();
    LivenessCoordinator coordinator = coordinator(true, Duration.ofSeconds(2));

    clock.advance(INTERVAL.minusSeconds(1));
    coordinator.verify(link);
    assertThat(clavaris.calls.get()).as("un segundo antes no renueva").isZero();

    clock.advance(Duration.ofSeconds(1));
    coordinator.verify(link);
    assertThat(clavaris.calls.get()).as("justo al cumplirse renueva").isEqualTo(1);
  }

  @Test
  void aPausedSessionIsRetriedExactlyWhenItsNegativeCacheEnds() {
    String link = login();
    clavaris.next = token -> new Result.Transient("http_503");
    LivenessCoordinator coordinator = coordinator(true, Duration.ofSeconds(2));
    clock.advance(INTERVAL);
    coordinator.verify(link);
    assertThat(clavaris.calls.get()).isEqualTo(1);

    clock.advance(Duration.ofSeconds(44));
    coordinator.verify(link);
    assertThat(clavaris.calls.get()).as("a los 44 s sigue en pausa").isEqualTo(1);

    clock.advance(Duration.ofSeconds(1));
    coordinator.verify(link);
    assertThat(clavaris.calls.get()).as("a los 45 s reintenta").isEqualTo(2);
  }

  @Test
  void theMarginIsExceededOnlyAfterItsExactLimit() {
    String link = login();
    clavaris.next = token -> new Result.Transient("http_503");
    LivenessCoordinator coordinator = coordinator(true, Duration.ofSeconds(2));

    clock.advance(MARGIN);
    assertThat(coordinator.verify(link))
        .as("justo en el margen sigue viva")
        .isEqualTo(Verdict.ALIVE);

    clock.advance(Duration.ofSeconds(1));
    assertThat(coordinator.verify(link))
        .as("un segundo después se cierra")
        .isEqualTo(Verdict.CLOSED);
  }

  // ---- la carrera entre dos instancias (ADR-0020, reglas 5 y 8) ----

  // Un almacén que avisa cuando la petición ya está dentro de `acquireLock`. Así la prueba actúa
  // justo entonces y no adivina con el reloj cuánto tarda el otro hilo en bloquearse.
  private LivenessStore storeThatSignalsWhenAskedForTheLock(CountDownLatch entered) {
    LivenessStore signalling = spy(store);
    doAnswer(
            invocation -> {
              entered.countDown();
              return invocation.callRealMethod();
            })
        .when(signalling)
        .acquireLock(any(), any(), any());
    return signalling;
  }

  @Test
  void aRequestThatWaitedForTheLockDoesNotRenewAgainWhenAnotherInstanceAlreadyDid()
      throws Exception {
    String link = login();
    clock.advance(INTERVAL);
    String holder = store.acquireLock(link, Duration.ofSeconds(30), Duration.ZERO).orElseThrow();
    CountDownLatch entered = new CountDownLatch(1);
    ExecutorService pool = Executors.newSingleThreadExecutor();
    try {
      Future<Verdict> waiting =
          pool.submit(
              () ->
                  coordinatorUsing(
                          storeThatSignalsWhenAskedForTheLock(entered),
                          true,
                          Duration.ofSeconds(10))
                      .verify(link));
      assertThat(entered.await(10, TimeUnit.SECONDS)).as("la petición llegó al cerrojo").isTrue();
      // La otra instancia renovó mientras tanto y deja el estado al día.
      store.recordRenewed(link, "sucesor", store.find(link).orElseThrow(), clock.instant());
      store.release(link, holder);

      assertThat(waiting.get(10, TimeUnit.SECONDS)).isEqualTo(Verdict.ALIVE);
    } finally {
      pool.shutdownNow();
    }
    assertThat(clavaris.calls.get()).as("no repite una renovación ya hecha").isZero();
  }

  @Test
  void aRequestThatWaitedForTheLockClosesWhenTheStateVanishedMeanwhile() throws Exception {
    String link = login();
    clock.advance(INTERVAL);
    String holder = store.acquireLock(link, Duration.ofSeconds(30), Duration.ZERO).orElseThrow();
    CountDownLatch entered = new CountDownLatch(1);
    ExecutorService pool = Executors.newSingleThreadExecutor();
    try {
      Future<Verdict> waiting =
          pool.submit(
              () ->
                  coordinatorUsing(
                          storeThatSignalsWhenAskedForTheLock(entered),
                          true,
                          Duration.ofSeconds(10))
                      .verify(link));
      assertThat(entered.await(10, TimeUnit.SECONDS)).as("la petición llegó al cerrojo").isTrue();
      store.delete(link); // otra petición cerró la sesión mientras esta esperaba
      store.release(link, holder);

      assertThat(waiting.get(10, TimeUnit.SECONDS)).isEqualTo(Verdict.CLOSED);
    } finally {
      pool.shutdownNow();
    }
    assertThat(clavaris.calls.get()).isZero();
  }

  @Test
  void aStateThatVanishesWhileWaitingForTheLockClosesTheSessionEvenIfTheWaitTimesOut()
      throws Exception {
    String link = login();
    clock.advance(INTERVAL);
    String holder = store.acquireLock(link, Duration.ofSeconds(30), Duration.ZERO).orElseThrow();
    CountDownLatch entered = new CountDownLatch(1);
    ExecutorService pool = Executors.newSingleThreadExecutor();
    try {
      Future<Verdict> waiting =
          pool.submit(
              () ->
                  coordinatorUsing(
                          storeThatSignalsWhenAskedForTheLock(entered),
                          true,
                          Duration.ofMillis(800))
                      .verify(link));
      assertThat(entered.await(10, TimeUnit.SECONDS)).as("la petición llegó al cerrojo").isTrue();
      store.delete(link); // el cerrojo sigue tomado: la espera termina sola a los 800 ms

      assertThat(waiting.get(10, TimeUnit.SECONDS)).isEqualTo(Verdict.CLOSED);
      assertThat(counted("lock_agotado")).isEqualTo(1);
      assertThat(counted("cerrada_sin_estado")).isEqualTo(1);
    } finally {
      pool.shutdownNow();
      store.release(link, holder);
    }
  }

  @Test
  void theCheckWorksWithoutAMetricsRegistry() {
    LivenessCoordinator withoutMetrics =
        new LivenessCoordinator(
            store,
            clavaris,
            new LivenessProperties(
                INTERVAL,
                MARGIN,
                true,
                false,
                Duration.ofSeconds(45),
                Duration.ofSeconds(30),
                Duration.ofSeconds(2),
                Duration.ofSeconds(2),
                Duration.ofSeconds(5)),
            MARGIN,
            clock,
            () -> null);
    String link = login();
    clock.advance(INTERVAL);

    assertThat(withoutMetrics.verify(link)).isEqualTo(Verdict.ALIVE);
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
