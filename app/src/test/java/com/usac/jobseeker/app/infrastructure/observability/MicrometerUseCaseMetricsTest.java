package com.usac.jobseeker.app.infrastructure.observability;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

class MicrometerUseCaseMetricsTest {

  private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
  private final MicrometerUseCaseMetrics metrics = new MicrometerUseCaseMetrics(meters);

  private double count(String useCase, String outcome) {
    var counter =
        meters
            .find("jobseeker_usecase_total")
            .tag("use_case", useCase)
            .tag("outcome", outcome)
            .counter();
    return counter == null ? 0 : counter.count();
  }

  @Test
  void eachCallIncrementsTheCounterOfItsUseCaseAndOutcome() {
    metrics.record("get_my_account", "found");
    metrics.record("get_my_account", "found");
    metrics.record("get_my_account", "not_found");

    assertThat(count("get_my_account", "found")).isEqualTo(2);
    assertThat(count("get_my_account", "not_found")).isOne();
  }

  @Test
  void useCasesAreIndependentUnderOneSingleMetricName() {
    metrics.record("get_my_account", "found");
    metrics.record("otro_caso_de_uso", "found");

    assertThat(count("otro_caso_de_uso", "found")).isOne();
    assertThat(meters.getMeters().stream().map(m -> m.getId().getName()).distinct())
        .containsExactly("jobseeker_usecase_total");
  }
}
