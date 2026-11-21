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
    assertThatThrownBy(() -> with(Duration.ofMinutes(16), Duration.ofSeconds(30)))
        .hasMessageContaining("15 minutos");
    assertThatThrownBy(() -> with(Duration.ZERO, Duration.ofSeconds(30)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void theLockMustOutliveTwoRenewalAttempts() {
    // 2 * (2 s + 5 s) = 14 s: un cerrojo de 14 s expiraría con el dueño aún renovando (regla 7).
    assertThatThrownBy(() -> with(Duration.ofMinutes(10), Duration.ofSeconds(14)))
        .hasMessageContaining("lock-ttl");
  }
}
