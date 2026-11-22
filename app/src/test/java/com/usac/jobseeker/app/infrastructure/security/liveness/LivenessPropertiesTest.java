package com.usac.jobseeker.app.infrastructure.security.liveness;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class LivenessPropertiesTest {

  private static LivenessProperties with(Duration interval, Duration lockTtl) {
    return new LivenessProperties(
        interval,
        Duration.ofHours(2),
        true,
        false,
        Duration.ofSeconds(45),
        lockTtl,
        Duration.ofSeconds(10),
        Duration.ofSeconds(2),
        Duration.ofSeconds(5));
  }

  @Test
  void theDefaultsSatisfyTheAdrRules() {
    assertThat(with(Duration.ofMinutes(10), Duration.ofSeconds(30)).renewalInterval())
        .isEqualTo(Duration.ofMinutes(10));
  }

  @Test
  void theRenewalIntervalCannotExceedFifteenMinutes() {
    // Las duraciones se crean fuera de la lambda: dentro debe haber una sola llamada que pueda
    // fallar.
    Duration tooLong = Duration.ofMinutes(16);
    Duration lockTtl = Duration.ofSeconds(30);
    assertThatThrownBy(() -> with(tooLong, lockTtl)).hasMessageContaining("15 minutos");
    assertThatThrownBy(() -> with(Duration.ZERO, lockTtl))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void theLockMustOutliveTwoRenewalAttempts() {
    // 2 * (2 s + 5 s) = 14 s: un cerrojo de 14 s expiraría con el dueño aún renovando (regla 7).
    Duration interval = Duration.ofMinutes(10);
    Duration tooShort = Duration.ofSeconds(14);
    assertThatThrownBy(() -> with(interval, tooShort)).hasMessageContaining("lock-ttl");
  }
}
