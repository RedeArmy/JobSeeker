package com.usac.jobseeker.app.infrastructure.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.usac.jobseeker.app.infrastructure.ratelimit.RateLimitProperties.KeyKind;
import com.usac.jobseeker.app.infrastructure.ratelimit.RateLimitProperties.Policy;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RateLimitPropertiesTest {

  private static Policy policy(KeyKind key) {
    return new Policy(10, 10, Duration.ofMinutes(1), key);
  }

  private static Map<String, Policy> all() {
    Map<String, Policy> policies = new HashMap<>();
    policies.put("login", policy(KeyKind.IP));
    policies.put("webhook", policy(KeyKind.IP));
    policies.put("write", policy(KeyKind.ACCOUNT));
    policies.put("read", policy(KeyKind.ACCOUNT));
    policies.put("suggestions", policy(KeyKind.ACCOUNT));
    return policies;
  }

  @Test
  void aCompleteSetOfPoliciesIsAccepted() {
    assertThat(new RateLimitProperties(true, all()).policies()).hasSize(5);
  }

  @Test
  void aMissingRequiredPolicyFailsAtStartupNamingIt() {
    for (String required : RateLimitProperties.REQUIRED) {
      Map<String, Policy> incomplete = all();
      incomplete.remove(required);

      assertThatThrownBy(() -> new RateLimitProperties(true, incomplete))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining(required);
    }
  }

  @Test
  void extraPoliciesAreAllowedForFutureEndpoints() {
    Map<String, Policy> extended = all();
    extended.put("matching-suggestions", policy(KeyKind.ACCOUNT));

    assertThat(new RateLimitProperties(true, extended).policies())
        .containsKey("matching-suggestions");
  }

  @Test
  void thePoliciesAreACopyThatCannotBeChangedAfterwards() {
    RateLimitProperties properties = new RateLimitProperties(true, all());
    Map<String, Policy> exposed = properties.policies();
    Policy extra = policy(KeyKind.IP);

    assertThatThrownBy(() -> exposed.put("otra", extra))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void aPolicyNeedsPositiveCapacityRefillAndPeriod() {
    Duration minute = Duration.ofMinutes(1);
    Duration zero = Duration.ZERO;
    Duration negative = Duration.ofSeconds(-1);

    assertThatThrownBy(() -> new Policy(0, 1, minute, KeyKind.IP))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new Policy(1, 0, minute, KeyKind.IP))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new Policy(1, 1, zero, KeyKind.IP))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new Policy(1, 1, negative, KeyKind.IP))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(new Policy(1, 1, Duration.ofNanos(1), KeyKind.ACCOUNT).capacity()).isOne();
  }
}
