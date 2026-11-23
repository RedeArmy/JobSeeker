package com.usac.jobseeker.app.infrastructure.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import com.usac.jobseeker.app.infrastructure.ratelimit.RateLimitProperties.KeyKind;
import com.usac.jobseeker.app.infrastructure.ratelimit.RateLimitProperties.Policy;
import com.usac.jobseeker.app.infrastructure.security.liveness.SharedRedis;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

/** El cubo de fichas contra un Redis real: lo que garantiza que el límite no se puede esquivar. */
class Bucket4jRedisRateLimiterIntegrationTest {

  private static final RedisClient CLIENT =
      RedisClient.create(RedisURI.create(SharedRedis.host(), SharedRedis.port()));
  private static final Policy TEN_PER_MINUTE =
      new Policy(10, 10, Duration.ofMinutes(1), KeyKind.ACCOUNT);

  private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
  private final Bucket4jRedisRateLimiter limiter = Bucket4jRedisRateLimiter.over(CLIENT, meters);

  @AfterAll
  static void close() {
    CLIENT.shutdown();
  }

  private static String key() {
    return UUID.randomUUID().toString();
  }

  private double count(String policy, String outcome) {
    var counter =
        meters
            .find("jobseeker_ratelimit_total")
            .tag("policy", policy)
            .tag("outcome", outcome)
            .counter();
    return counter == null ? 0 : counter.count();
  }

  @Test
  void theFirstTokensAreGrantedCountingDownAndThenItIsRefusedWithAWaitTime() {
    String key = key();
    long previousRemaining = TEN_PER_MINUTE.capacity();

    for (int i = 0; i < TEN_PER_MINUTE.capacity(); i++) {
      RateLimitDecision decision = limiter.tryConsume("read", TEN_PER_MINUTE, key);
      assertThat(decision.allowed()).as("intento %d", i + 1).isTrue();
      assertThat(decision.limit()).isEqualTo(10);
      assertThat(decision.remaining()).isEqualTo(previousRemaining - 1);
      previousRemaining = decision.remaining();
    }

    RateLimitDecision refused = limiter.tryConsume("read", TEN_PER_MINUTE, key);
    assertThat(refused.allowed()).isFalse();
    assertThat(refused.remaining()).isZero();
    assertThat(refused.retryAfterSeconds()).isBetween(1L, 60L);
    assertThat(count("read", "allowed")).isEqualTo(10);
    assertThat(count("read", "limited")).isOne();
  }

  @Test
  void eachKeyHasItsOwnBucket() {
    String first = key();
    String second = key();
    for (int i = 0; i < TEN_PER_MINUTE.capacity(); i++) {
      limiter.tryConsume("read", TEN_PER_MINUTE, first);
    }

    assertThat(limiter.tryConsume("read", TEN_PER_MINUTE, first).allowed()).isFalse();
    assertThat(limiter.tryConsume("read", TEN_PER_MINUTE, second).allowed()).isTrue();
  }

  @Test
  void eachPolicyHasItsOwnBucketForTheSameKey() {
    String key = key();
    for (int i = 0; i < TEN_PER_MINUTE.capacity(); i++) {
      limiter.tryConsume("read", TEN_PER_MINUTE, key);
    }

    assertThat(limiter.tryConsume("read", TEN_PER_MINUTE, key).allowed()).isFalse();
    assertThat(limiter.tryConsume("write", TEN_PER_MINUTE, key).allowed()).isTrue();
  }

  @Test
  void theBucketLivesInRedisUnderTheDocumentedKeySpace() {
    String key = key();

    limiter.tryConsume("read", TEN_PER_MINUTE, key);

    assertThat(SharedRedis.template().hasKey("ratelimit:read:" + key)).isTrue();
  }

  @Test
  void aBurstOfSimultaneousRequestsNeverGrantsMoreThanTheCapacity() throws Exception {
    String key = key();
    int attempts = 200;
    CountDownLatch together = new CountDownLatch(1);
    ExecutorService pool = Executors.newFixedThreadPool(16);
    int granted = 0;
    try {
      List<Future<Boolean>> results = new ArrayList<>();
      for (int i = 0; i < attempts; i++) {
        results.add(
            pool.submit(
                () -> {
                  together.await();
                  return limiter.tryConsume("read", TEN_PER_MINUTE, key).allowed();
                }));
      }
      together.countDown();
      for (Future<Boolean> result : results) {
        if (result.get(60, TimeUnit.SECONDS)) {
          granted++;
        }
      }
    } finally {
      pool.shutdownNow();
    }

    assertThat(granted).as("exactamente la capacidad, ni una ficha más").isEqualTo(10);
  }

  @Test
  void ifRedisIsDownTheRequestIsLetThroughAndTheOutageIsCounted() {
    RedisClient unreachable = RedisClient.create(RedisURI.create("127.0.0.1", 1));
    try {
      Bucket4jRedisRateLimiter blind = Bucket4jRedisRateLimiter.over(unreachable, meters);

      RateLimitDecision decision = blind.tryConsume("read", TEN_PER_MINUTE, key());

      assertThat(decision.allowed()).isTrue();
      assertThat(decision.limit()).isEqualTo(10);
      assertThat(count("read", "unavailable")).isOne();
      assertThat(blind.tryConsume("read", TEN_PER_MINUTE, key()).allowed())
          .as("tampoco reintenta la conexión de inmediato")
          .isTrue();
      assertThat(count("read", "unavailable")).isEqualTo(2);
    } finally {
      unreachable.shutdown();
    }
  }

  @Test
  void thePolicyParametersDecideTheBucketSize() {
    Policy tiny = new Policy(2, 1, Duration.ofMinutes(1), KeyKind.IP);
    String key = key();

    assertThat(limiter.tryConsume("login", tiny, key).allowed()).isTrue();
    assertThat(limiter.tryConsume("login", tiny, key).allowed()).isTrue();
    assertThat(limiter.tryConsume("login", tiny, key).allowed()).isFalse();
  }
}
