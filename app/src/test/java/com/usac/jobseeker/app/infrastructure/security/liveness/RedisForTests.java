package com.usac.jobseeker.app.infrastructure.security.liveness;

import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Un Redis real para las pruebas del paquete, sin levantar la aplicación: se arranca una vez por
 * JVM y Testcontainers lo retira al terminar. Cada prueba usa enlaces nuevos, así que compartirlo
 * no las acopla.
 */
final class RedisForTests {

  private static final GenericContainer<?> REDIS =
      new GenericContainer<>(DockerImageName.parse("redis:8.10.3-alpine")).withExposedPorts(6379);
  private static StringRedisTemplate template;

  private RedisForTests() {}

  static synchronized StringRedisTemplate template() {
    if (template == null) {
      REDIS.start();
      LettuceConnectionFactory factory =
          new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
      factory.afterPropertiesSet();
      template = new StringRedisTemplate(factory);
      template.afterPropertiesSet();
    }
    return template;
  }
}
