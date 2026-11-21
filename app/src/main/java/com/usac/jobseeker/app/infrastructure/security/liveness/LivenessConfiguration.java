package com.usac.jobseeker.app.infrastructure.security.liveness;

import com.usac.jobseeker.app.infrastructure.security.ClavarisProperties;
import com.usac.jobseeker.app.infrastructure.security.SessionProperties;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizedClientRepository;

/** Piezas de la prueba de vida de la sesión (ADR-0020), cableadas aquí y no en sus clases. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({LivenessProperties.class, EncryptionProperties.class})
class LivenessConfiguration {

  // Con Clavaris configurado y sin clave de cifrado nadie podría iniciar sesión: es un error de
  // despliegue y se falla al arrancar, con un mensaje que dice qué falta. Sin Clavaris configurado
  // la aplicación arranca igual (el login responde «no configurado»).
  @Bean
  RefreshTokenCipher refreshTokenCipher(
      EncryptionProperties encryption, ClavarisProperties clavaris) {
    RefreshTokenCipher cipher = RefreshTokenCipher.from(encryption);
    if (clavaris.configured() && !cipher.available()) {
      throw new IllegalStateException(
          "Clavaris está configurado pero falta TOKEN_ENCRYPTION_KEYS (y TOKEN_ENCRYPTION_ACTIVE_KID): "
              + "genera una con `openssl rand -base64 32`");
    }
    return cipher;
  }

  @Bean
  LivenessStore livenessStore(
      StringRedisTemplate redis, RefreshTokenCipher cipher, SessionProperties session) {
    // El registro no puede vivir más que la sesión: su límite absoluto es lo más que dura.
    return new LivenessStore(redis, cipher, session.absoluteTimeout());
  }

  @Bean
  RefreshTokenClient refreshTokenClient(
      ClientRegistrationRepository clients, LivenessProperties liveness) {
    return new ClavarisRefreshTokenClient(clients, liveness);
  }

  @Bean
  LivenessCoordinator livenessCoordinator(
      LivenessStore store,
      RefreshTokenClient client,
      LivenessProperties liveness,
      SessionProperties session,
      Clock clock,
      ObjectProvider<MeterRegistry> meters) {
    // ADR-0020, regla 2: el margen sin Clavaris nunca supera el límite absoluto de la sesión.
    Duration margin =
        liveness.clavarisUnavailableMargin().compareTo(session.absoluteTimeout()) > 0
            ? session.absoluteTimeout()
            : liveness.clavarisUnavailableMargin();
    return new LivenessCoordinator(store, client, liveness, margin, clock, meters::getIfAvailable);
  }

  @Bean
  OAuth2AuthorizedClientRepository authorizedClientRepository(
      LivenessStore store, RefreshTokenCipher cipher, Clock clock) {
    return new VaultingAuthorizedClientRepository(store, cipher, clock);
  }
}
