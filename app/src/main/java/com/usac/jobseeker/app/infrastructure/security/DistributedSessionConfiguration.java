package com.usac.jobseeker.app.infrastructure.security;

import com.usac.jobseeker.app.infrastructure.security.webhook.SessionInvalidationConsumer;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.session.config.SessionRepositoryCustomizer;
import org.springframework.session.data.redis.RedisIndexedSessionRepository;
import org.springframework.session.data.redis.config.ConfigureRedisAction;
import org.springframework.session.data.redis.config.annotation.web.http.EnableRedisIndexedHttpSession;
import org.springframework.session.web.http.CookieSerializer;
import org.springframework.session.web.http.DefaultCookieSerializer;

/**
 * Sesión web del lado del servidor sobre Redis (ADR-0020). En Spring Boot 4 `spring.session.*` no
 * configura nada: se declara explícitamente, y con el repositorio **indexado** por cuenta
 * (`PRINCIPAL_NAME_INDEX_NAME` = `accountId`), lo que permite cerrar todas las sesiones de una
 * cuenta al recibir un webhook (sub-paso 4.5).
 */
@Configuration(proxyBeanMethods = false)
@EnableRedisIndexedHttpSession
class DistributedSessionConfiguration {

  // Regla 9a: sin `CONFIG SET notify-keyspace-events` al arrancar. Falla con Redis caído y está
  // deshabilitado en un Redis gestionado; el servidor debe traer `notify-keyspace-events Egx`.
  @Bean
  static ConfigureRedisAction configureRedisAction() {
    return ConfigureRedisAction.NO_OP;
  }

  // Regla 9b: el oyente de sesiones no debe impedir el arranque con Redis caído.
  @Bean
  static TolerantSessionListenerStartup tolerantSessionListenerStartup(
      ObjectProvider<MeterRegistry> meters) {
    return new TolerantSessionListenerStartup(meters::getIfAvailable);
  }

  // Consumidor de la bandeja que cierra las sesiones de una cuenta (webhooks de Clavaris, 4.5).
  @Bean
  SessionInvalidationConsumer sessionInvalidationConsumer(RedisIndexedSessionRepository sessions) {
    return new SessionInvalidationConsumer(sessions);
  }

  @Bean
  SessionRepositoryCustomizer<RedisIndexedSessionRepository> idleTimeout(
      SessionProperties session) {
    return repository -> repository.setDefaultMaxInactiveInterval(session.idleTimeout());
  }

  // Cookie: HttpOnly, SameSite=Lax y Path=/. El prefijo `__Host-` exige HTTPS (Secure), por eso
  // solo
  // el perfil prod lo usa; en desarrollo sobre http://localhost el mismo nombre va sin prefijo.
  @Bean
  CookieSerializer cookieSerializer(SessionProperties session) {
    DefaultCookieSerializer serializer = new DefaultCookieSerializer();
    serializer.setCookieName(session.cookieName());
    serializer.setCookiePath("/");
    serializer.setUseHttpOnlyCookie(true);
    serializer.setSameSite("Lax");
    if (session.secureCookie()) {
      serializer.setUseSecureCookie(true);
    }
    return serializer;
  }
}
