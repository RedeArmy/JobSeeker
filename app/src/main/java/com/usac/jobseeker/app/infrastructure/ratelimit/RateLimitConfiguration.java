package com.usac.jobseeker.app.infrastructure.ratelimit;

import io.lettuce.core.ClientOptions;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.SocketOptions;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.data.redis.autoconfigure.DataRedisConnectionDetails;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Cableado del límite de tasa; los filtros los toma `SecurityConfiguration` de {@link
 * RateLimitFilters}.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(RateLimitProperties.class)
class RateLimitConfiguration {

  /** Tiempo máximo que el límite de tasa puede añadir a una petición cuando Redis no responde. */
  private static final Duration REDIS_TIMEOUT = Duration.ofMillis(250);

  /**
   * Cliente propio de Lettuce, aparte del de Spring Data: Bucket4j necesita la conexión con
   * codificación de bytes y un tiempo de espera corto, y no debe compartir la de las sesiones. El
   * destino sale de los detalles de conexión de Spring Boot y no de las propiedades: así sigue al
   * Redis que realmente usa el resto de la aplicación (p. ej. el contenedor de las pruebas).
   */
  @Bean(destroyMethod = "shutdown")
  RedisClient rateLimitRedisClient(DataRedisConnectionDetails redis) {
    RedisClient client = RedisClient.create(uriOf(redis));
    client.setOptions(
        ClientOptions.builder()
            .socketOptions(SocketOptions.builder().connectTimeout(REDIS_TIMEOUT).build())
            .build());
    return client;
  }

  static RedisURI uriOf(DataRedisConnectionDetails redis) {
    DataRedisConnectionDetails.Standalone node = redis.getStandalone();
    RedisURI.Builder uri =
        RedisURI.builder()
            .withHost(node.getHost())
            .withPort(node.getPort())
            .withTimeout(REDIS_TIMEOUT);
    if (redis.getPassword() != null && !redis.getPassword().isEmpty()) {
      uri.withPassword(redis.getPassword().toCharArray());
    }
    return uri.build();
  }

  @Bean
  RateLimiter rateLimiter(RedisClient rateLimitRedisClient, MeterRegistry meters) {
    return Bucket4jRedisRateLimiter.over(rateLimitRedisClient, meters);
  }

  @Bean
  RateLimitFilters rateLimitFilters(RateLimitProperties properties, RateLimiter limiter) {
    return new RateLimitFilters(properties, limiter);
  }
}
