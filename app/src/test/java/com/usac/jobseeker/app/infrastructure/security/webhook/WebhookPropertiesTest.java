package com.usac.jobseeker.app.infrastructure.security.webhook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class WebhookPropertiesTest {

  private static WebhookProperties props(String secret, String previous) {
    return new WebhookProperties(secret, previous, Duration.ofMinutes(5), 1024);
  }

  @Test
  void withoutASecretTheReceiverIsNotConfigured() {
    assertThat(props("", "").configured()).isFalse();
    assertThat(props(null, null).configured()).isFalse();
    assertThat(props("  ", "  ").secrets()).isEmpty();
  }

  @Test
  void onlyThePreviousSecretIsNotEnoughToBeConfiguredFromTheCurrentButStillVerifies() {
    assertThat(props("", "viejo").secrets()).containsExactly("viejo");
  }

  @Test
  void currentAndPreviousAreBothUsedInThatOrderAndTrimmed() {
    assertThat(props(" nuevo ", " viejo ").secrets()).containsExactly("nuevo", "viejo");
  }

  @Test
  void aPreviousSecretEqualToTheCurrentIsNotDuplicated() {
    assertThat(props("igual", "igual").secrets()).containsExactly("igual");
  }

  @Test
  void theToleranceMustBePositiveAndAtMostFifteenMinutes() {
    Duration max = Duration.ofMinutes(15);
    Duration overMax = max.plusSeconds(1);
    Duration negative = Duration.ofSeconds(-1);
    Duration zero = Duration.ZERO;
    assertThat(new WebhookProperties("s", "", max, 1).tolerance()).isEqualTo(max);
    assertThatThrownBy(() -> new WebhookProperties("s", "", zero, 1))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new WebhookProperties("s", "", negative, 1))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new WebhookProperties("s", "", overMax, 1))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void theBodyLimitMustBePositive() {
    Duration tolerance = Duration.ofMinutes(5);
    assertThatThrownBy(() -> new WebhookProperties("s", "", tolerance, 0))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
