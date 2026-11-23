package com.usac.jobseeker.app.infrastructure.observability;

import com.usac.jobseeker.common.application.observability.UseCaseMetricsPort;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * Traduce lo que dice un caso de uso a un contador de Micrometer (ADR-0012). Una sola métrica para
 * todos los casos de uso, con etiquetas de cardinalidad acotada (`use_case`, `outcome`): añadir un
 * caso de uso no crea una métrica nueva ni obliga a tocar los paneles de Grafana.
 */
public class MicrometerUseCaseMetrics implements UseCaseMetricsPort {

  static final String METRIC = "jobseeker_usecase_total";

  private final MeterRegistry meters;

  public MicrometerUseCaseMetrics(MeterRegistry meters) {
    this.meters = meters;
  }

  @Override
  public void count(String useCase, String outcome) {
    meters.counter(METRIC, "use_case", useCase, "outcome", outcome).increment();
  }
}
