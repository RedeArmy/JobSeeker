package com.usac.jobseeker.app.infrastructure.observability;

import com.usac.jobseeker.common.application.observability.UseCaseMetricsPort;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Proveedor del puerto de métricas de negocio que usan los casos de uso. */
@Configuration(proxyBeanMethods = false)
class ObservabilityConfiguration {

  @Bean
  UseCaseMetricsPort useCaseMetrics(MeterRegistry meters) {
    return new MicrometerUseCaseMetrics(meters);
  }
}
