package com.usac.jobseeker.matching.application.usecase.suggestcandidatesforjob;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class SuggestionsPolicyTest {

  @Test
  void aCoherentPolicyIsAccepted() {
    var policy = new SuggestionsPolicy(200, Duration.ofHours(24), 50);

    assertThat(policy.viewQuota()).isEqualTo(200);
    assertThat(policy.maxPageSize()).isEqualTo(50);
  }

  @Test
  void aZeroQuotaIsValidAndMeansNobodyViewsAnythingNew() {
    assertThat(new SuggestionsPolicy(0, Duration.ofHours(1), 1).viewQuota()).isZero();
  }

  @Test
  void anIncoherentPolicyIsRefusedAtStartup() {
    Duration day = Duration.ofHours(24);
    Duration negative = Duration.ofHours(-1);

    assertThatThrownBy(() -> new SuggestionsPolicy(-1, day, 50))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new SuggestionsPolicy(10, day, 0))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new SuggestionsPolicy(10, Duration.ZERO, 50))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new SuggestionsPolicy(10, negative, 50))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new SuggestionsPolicy(10, null, 50))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
