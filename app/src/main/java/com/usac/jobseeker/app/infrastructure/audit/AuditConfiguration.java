package com.usac.jobseeker.app.infrastructure.audit;

import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** La retención de la auditoría (TD-010); el escritor, `JdbcAuditLog`, ya es un componente. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AuditProperties.class)
class AuditConfiguration {

  @Bean
  AuditRetentionJob auditRetentionJob(
      JdbcAuditLog audit, AuditProperties properties, MeterRegistry meters) {
    return new AuditRetentionJob(audit, properties, meters, Clock.systemUTC());
  }
}
