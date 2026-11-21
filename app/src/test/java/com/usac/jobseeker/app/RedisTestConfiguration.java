package com.usac.jobseeker.app;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

/** Redis 7.4, la misma versión mayor que el stack local (sesiones, ADR-0020). */
@TestConfiguration(proxyBeanMethods = false)
public class RedisTestConfiguration {

  @Bean
  @ServiceConnection(name = "redis")
  GenericContainer<?> redis() {
    return new GenericContainer<>(DockerImageName.parse("redis:7.4.12-alpine"))
        .withExposedPorts(6379);
  }
}
