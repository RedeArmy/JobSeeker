package com.usac.jobseeker.app.infrastructure.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class OutboxPropertiesTest {

  private static final OutboxProperties DEFAULTS =
      new OutboxProperties(
          Duration.ofSeconds(1), 50, 10, Duration.ofSeconds(2), Duration.ofMinutes(5));

  @Test
  void theBackoffDoublesPerAttemptAndNeverExceedsTheCap() {
    // Con jitter 1 el retroceso es el máximo del intervalo: 2 s, 4 s, 8 s…
    assertThat(DEFAULTS.backoff(1, 1.0)).isEqualTo(Duration.ofSeconds(2));
    assertThat(DEFAULTS.backoff(2, 1.0)).isEqualTo(Duration.ofSeconds(4));
    assertThat(DEFAULTS.backoff(3, 1.0)).isEqualTo(Duration.ofSeconds(8));
    assertThat(DEFAULTS.backoff(30, 1.0)).isEqualTo(Duration.ofMinutes(5));
    assertThat(DEFAULTS.backoff(10_000, 1.0)).isEqualTo(Duration.ofMinutes(5));
  }

  @Test
  void jitterSpreadsTheDelayBetweenHalfAndTheFullValueAndIsNeverZero() {
    assertThat(DEFAULTS.backoff(3, 0.0)).isEqualTo(Duration.ofSeconds(4));
    assertThat(DEFAULTS.backoff(3, 0.5)).isEqualTo(Duration.ofSeconds(6));
    assertThat(
            new OutboxProperties(
                    Duration.ofSeconds(1), 1, 1, Duration.ofMillis(1), Duration.ofMillis(1))
                .backoff(1, 0.0))
        .isPositive();
  }

  @Test
  void anAttemptBelowOneIsTreatedAsTheFirst() {
    assertThat(DEFAULTS.backoff(0, 1.0)).isEqualTo(Duration.ofSeconds(2));
    assertThat(DEFAULTS.backoff(-3, 1.0)).isEqualTo(Duration.ofSeconds(2));
  }

  @Test
  void invalidSettingsAreRejectedAtStartup() {
    Duration s = Duration.ofSeconds(1);
    assertThatThrownBy(() -> new OutboxProperties(Duration.ZERO, 1, 1, s, s))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new OutboxProperties(s, 0, 1, s, s))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new OutboxProperties(s, 1, 0, s, s))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new OutboxProperties(s, 1, 1, Duration.ZERO, s))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new OutboxProperties(s, 1, 1, Duration.ofSeconds(2), s))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void negativeDurationsAreRejectedJustLikeZero() {
    Duration s = Duration.ofSeconds(1);
    Duration negative = Duration.ofSeconds(-1);
    assertThatThrownBy(() -> new OutboxProperties(negative, 1, 1, s, s))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new OutboxProperties(s, 1, 1, negative, s))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void theSmallestPositiveValuesAreAccepted() {
    Duration tick = Duration.ofNanos(1);
    assertThat(new OutboxProperties(tick, 1, 1, tick, tick).maxAttempts()).isOne();
  }
}
