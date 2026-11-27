package com.usac.jobseeker.app.infrastructure.idempotency;

import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** La retención de las claves de idempotencia (TD-010); el escritor ya es un componente. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(IdempotencyProperties.class)
class IdempotencyConfiguration {

  @Bean
  IdempotencyRetentionJob idempotencyRetentionJob(
      JdbcIdempotencyKeys keys, IdempotencyProperties properties, MeterRegistry meters) {
    return new IdempotencyRetentionJob(keys, properties, meters, Clock.systemUTC());
  }
}
