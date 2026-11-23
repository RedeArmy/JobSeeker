package com.usac.jobseeker.app.infrastructure.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import io.lettuce.core.RedisURI;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.data.redis.autoconfigure.DataRedisConnectionDetails;

/** El destino del límite de tasa sale de los detalles de conexión de Spring Boot. */
class RateLimitConfigurationTest {

  private static DataRedisConnectionDetails details(String host, int port, String password) {
    return new DataRedisConnectionDetails() {
      @Override
      public String getPassword() {
        return password;
      }

      @Override
      public Standalone getStandalone() {
        return Standalone.of(host, port);
      }
    };
  }

  @Test
  void theUriFollowsTheHostAndPortOfTheConnectionDetailsWithAShortTimeout() {
    RedisURI uri = RateLimitConfiguration.uriOf(details("redis.interno", 6380, null));

    assertThat(uri.getHost()).isEqualTo("redis.interno");
    assertThat(uri.getPort()).isEqualTo(6380);
    assertThat(uri.getTimeout()).isEqualTo(Duration.ofMillis(250));
  }

  /** En Lettuce 7 la contraseña solo se lee a través del proveedor de credenciales. */
  private static String passwordOf(RedisURI uri) {
    var provider = uri.getCredentialsProvider();
    if (provider == null) {
      return null;
    }
    var credentials = provider.resolveCredentials().block();
    return credentials == null || !credentials.hasPassword()
        ? null
        : new String(credentials.getPassword());
  }

  @Test
  void aPasswordIsPassedOnAndAbsentOrEmptyOnesAreNot() {
    assertThat(passwordOf(RateLimitConfiguration.uriOf(details("h", 1, "secreto"))))
        .isEqualTo("secreto");
    assertThat(passwordOf(RateLimitConfiguration.uriOf(details("h", 1, null)))).isNull();
    assertThat(passwordOf(RateLimitConfiguration.uriOf(details("h", 1, "")))).isNull();
  }
}
