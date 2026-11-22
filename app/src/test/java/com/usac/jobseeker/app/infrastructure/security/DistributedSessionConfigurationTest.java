package com.usac.jobseeker.app.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.session.data.redis.RedisIndexedSessionRepository;
import org.springframework.session.data.redis.config.ConfigureRedisAction;
import org.springframework.session.web.http.CookieSerializer;

/**
 * La sesión web (ADR-0020): la cookie que recibe el navegador, la inactividad y que Spring Session
 * no toque la configuración del servidor Redis.
 */
class DistributedSessionConfigurationTest {

  private final DistributedSessionConfiguration configuration =
      new DistributedSessionConfiguration();

  private static SessionProperties session(String cookieName, boolean secure) {
    return new SessionProperties(Duration.ofMinutes(30), Duration.ofHours(8), cookieName, secure);
  }

  private String setCookieFor(CookieSerializer serializer) {
    MockHttpServletRequest request = new MockHttpServletRequest();
    MockHttpServletResponse response = new MockHttpServletResponse();
    serializer.writeCookieValue(
        new CookieSerializer.CookieValue(request, response, "valor-de-sesion"));
    List<String> headers = response.getHeaders("Set-Cookie");
    assertThat(headers).hasSize(1);
    return headers.getFirst();
  }

  @Test
  void theSessionCookieIsHttpOnlySameSiteLaxAndScopedToTheWholeSite() {
    String cookie = setCookieFor(configuration.cookieSerializer(session("SESSION", false)));

    assertThat(cookie)
        .startsWith("SESSION=")
        .contains("HttpOnly")
        .contains("SameSite=Lax")
        .contains("Path=/");
  }

  @Test
  void inDevelopmentTheCookieIsNotSecureSoItWorksOverHttpLocalhost() {
    String cookie = setCookieFor(configuration.cookieSerializer(session("SESSION", false)));

    assertThat(cookie).doesNotContain("Secure");
  }

  @Test
  void inProductionTheCookieIsSecureAndKeepsItsHostPrefixName() {
    String cookie = setCookieFor(configuration.cookieSerializer(session("__Host-SESSION", true)));

    assertThat(cookie).startsWith("__Host-SESSION=").contains("Secure").contains("Path=/");
    assertThat(cookie).as("el prefijo __Host- prohíbe Domain").doesNotContain("Domain");
  }

  @Test
  void theCookieNameComesFromTheConfigurationAndIsNotHardcoded() {
    assertThat(setCookieFor(configuration.cookieSerializer(session("OTRA", false))))
        .startsWith("OTRA=");
  }

  @Test
  void theIdleTimeoutOfTheConfigurationIsAppliedToTheSessionRepository() {
    RedisIndexedSessionRepository repository = mock(RedisIndexedSessionRepository.class);

    configuration.idleTimeout(session("SESSION", false)).customize(repository);

    verify(repository).setDefaultMaxInactiveInterval(Duration.ofMinutes(30));
  }

  @Test
  void springSessionIsNeverAllowedToChangeTheRedisServerConfiguration() {
    assertThat(DistributedSessionConfiguration.configureRedisAction())
        .isSameAs(ConfigureRedisAction.NO_OP);
  }

  @Test
  void theTolerantStartupIsProvidedSoARedisOutageDoesNotStopTheProcess() {
    assertThat(
            DistributedSessionConfiguration.tolerantSessionListenerStartup(
                new org.springframework.beans.factory.support.StaticListableBeanFactory()
                    .getBeanProvider(io.micrometer.core.instrument.MeterRegistry.class)))
        .isNotNull();
  }
}
