package com.usac.jobseeker.app.infrastructure.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import com.usac.jobseeker.app.infrastructure.ratelimit.RateLimitProperties.KeyKind;
import com.usac.jobseeker.app.infrastructure.ratelimit.RateLimitProperties.Policy;
import com.usac.jobseeker.app.infrastructure.security.liveness.SharedRedis;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.codec.ByteArrayCodec;
import io.lettuce.core.codec.RedisCodec;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/**
 * Cómo se porta el limitador cuando Redis cae, con un reloj controlado: no se reintenta la conexión
 * dentro del margen, se reintenta pasado, se recupera solo y el aviso no se repite en cada
 * petición.
 */
@ExtendWith(OutputCaptureExtension.class)
class Bucket4jRedisRateLimiterOutageTest {

  private static final Policy POLICY = new Policy(5, 5, Duration.ofMinutes(1), KeyKind.ACCOUNT);
  private static final long SECOND = Duration.ofSeconds(1).toNanos();
  private static final String WARNING = "event=ratelimit_unavailable";

  private final AtomicLong nanos = new AtomicLong(1_000_000_000_000L);
  private final AtomicInteger attempts = new AtomicInteger();
  private final AtomicBoolean redisIsUp = new AtomicBoolean(false);
  private final SimpleMeterRegistry meters = new SimpleMeterRegistry();

  private final Supplier<StatefulRedisConnection<byte[], byte[]>> connector =
      () -> {
        attempts.incrementAndGet();
        if (!redisIsUp.get()) {
          throw new IllegalStateException("Redis caído");
        }
        RedisClient client =
            RedisClient.create(RedisURI.create(SharedRedis.host(), SharedRedis.port()));
        return client.connect(RedisCodec.of(ByteArrayCodec.INSTANCE, ByteArrayCodec.INSTANCE));
      };

  private final Bucket4jRedisRateLimiter limiter =
      new Bucket4jRedisRateLimiter(connector, meters, nanos::get);

  private RateLimitDecision call() {
    return limiter.tryConsume("read", POLICY, "outage-" + System.identityHashCode(this));
  }

  private static long occurrences(String text, String needle) {
    return text.lines().filter(line -> line.contains(needle)).count();
  }

  @Test
  void aFailedConnectionIsNotRetriedUntilTheBackoffHasPassed() {
    call();
    assertThat(attempts).hasValue(1);

    nanos.addAndGet(5 * SECOND - 1);
    call();
    assertThat(attempts).as("un nanosegundo antes del margen").hasValue(1);

    nanos.addAndGet(1);
    call();
    assertThat(attempts).as("justo al cumplirse el margen").hasValue(2);
  }

  @Test
  void everyRequestDuringTheOutageIsLetThroughAndCounted() {
    for (int i = 0; i < 4; i++) {
      assertThat(call().allowed()).isTrue();
    }

    assertThat(
            meters
                .counter("jobseeker_ratelimit_total", "policy", "read", "outcome", "unavailable")
                .count())
        .isEqualTo(4);
  }

  @Test
  void onceRedisIsBackTheLimiterRecoversByItselfAndStartsLimiting() {
    call();
    redisIsUp.set(true);
    nanos.addAndGet(5 * SECOND);

    for (int i = 0; i < POLICY.capacity(); i++) {
      assertThat(call().allowed()).isTrue();
    }

    assertThat(call().allowed()).as("ya limita de verdad").isFalse();
  }

  @Test
  void theOutageWarningIsLoggedOncePer30SecondsAndNotOncePerRequest(CapturedOutput output) {
    for (int i = 0; i < 10; i++) {
      call();
    }
    assertThat(occurrences(output.getAll(), WARNING)).as("diez peticiones, un aviso").isOne();

    nanos.addAndGet(30 * SECOND - 1);
    call();
    assertThat(occurrences(output.getAll(), WARNING)).as("a punto de cumplirse").isOne();

    nanos.addAndGet(1);
    call();
    assertThat(occurrences(output.getAll(), WARNING)).as("cumplidos los 30 s").isEqualTo(2);
  }

  @Test
  void theFirstWarningIsLoggedEvenIfTheClockStartsNearZero(CapturedOutput output) {
    // `System.nanoTime` no tiene origen definido: puede ser pequeño o negativo. El primer aviso no
    // puede depender de que hayan pasado 30 s desde un instante que nunca existió.
    nanos.set(0);

    call();

    assertThat(occurrences(output.getAll(), WARNING)).isOne();
  }
}
