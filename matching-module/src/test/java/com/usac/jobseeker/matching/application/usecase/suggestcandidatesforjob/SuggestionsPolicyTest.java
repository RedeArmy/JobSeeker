package com.usac.jobseeker.matching.application.usecase.suggestcandidatesforjob;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class SuggestionsPolicyTest {

  @Test
  void aCoherentPolicyIsAccepted() {
    var policy = new SuggestionsPolicy(200, Duration.ofHours(24), 50, 200, 0.5);

    assertThat(policy.viewQuota()).isEqualTo(200);
    assertThat(policy.maxPageSize()).isEqualTo(50);
    assertThat(policy.retrievalCap()).isEqualTo(200);
    assertThat(policy.tieTolerance()).isEqualTo(0.5);
  }

  @Test
  void aZeroQuotaIsValidAndMeansNobodyViewsAnythingNew() {
    assertThat(new SuggestionsPolicy(0, Duration.ofHours(1), 1, 1, 0.5).viewQuota()).isZero();
  }

  @Test
  void anIncoherentPolicyIsRefusedAtStartup() {
    Duration day = Duration.ofHours(24);
    Duration negative = Duration.ofHours(-1);

    assertThatThrownBy(() -> new SuggestionsPolicy(-1, day, 50, 200, 0.5))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new SuggestionsPolicy(10, day, 0, 200, 0.5))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new SuggestionsPolicy(10, Duration.ZERO, 50, 200, 0.5))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new SuggestionsPolicy(10, negative, 50, 200, 0.5))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new SuggestionsPolicy(10, null, 50, 200, 0.5))
        .isInstanceOf(IllegalArgumentException.class);
    // La tolerancia de empate va de 0 a 10 puntos.
    assertThatThrownBy(() -> new SuggestionsPolicy(10, day, 50, 200, -0.1))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new SuggestionsPolicy(10, day, 50, 200, 10.1))
        .isInstanceOf(IllegalArgumentException.class);
    // Recuperar menos candidatos que los de una página no tiene sentido.
    assertThatThrownBy(() -> new SuggestionsPolicy(10, day, 50, 49, 0.5))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
