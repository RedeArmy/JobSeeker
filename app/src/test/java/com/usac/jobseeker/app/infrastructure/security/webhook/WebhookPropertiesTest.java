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
    assertThat(new WebhookProperties("s", "", Duration.ofMinutes(15), 1).tolerance())
        .isEqualTo(Duration.ofMinutes(15));
    assertThatThrownBy(() -> new WebhookProperties("s", "", Duration.ZERO, 1))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new WebhookProperties("s", "", Duration.ofSeconds(-1), 1))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> new WebhookProperties("s", "", Duration.ofMinutes(15).plusSeconds(1), 1))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void theBodyLimitMustBePositive() {
    assertThatThrownBy(() -> new WebhookProperties("s", "", Duration.ofMinutes(5), 0))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
