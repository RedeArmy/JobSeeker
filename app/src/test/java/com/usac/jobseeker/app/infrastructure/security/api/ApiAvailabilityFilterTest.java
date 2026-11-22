package com.usac.jobseeker.app.infrastructure.security.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import jakarta.servlet.ServletException;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.oauth2.jwt.JwtException;

/**
 * Cuando no se puede comprobar el token porque Clavaris no responde, la API contesta 503 con
 * `Retry-After`: ni un 401 que acusaría al token, ni un 500.
 */
class ApiAvailabilityFilterTest {

  private final ApiAvailabilityFilter filter = new ApiAvailabilityFilter(Duration.ofSeconds(10));
  private final MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/x");
  private final MockHttpServletResponse response = new MockHttpServletResponse();

  private static MockFilterChain failingWith(RuntimeException failure) {
    return new MockFilterChain() {
      @Override
      public void doFilter(
          jakarta.servlet.ServletRequest req, jakarta.servlet.ServletResponse res) {
        throw failure;
      }
    };
  }

  @Test
  void aFailureThatIsNotTheTokensFaultBecomesA503WithRetryAfterAndTheStandardEnvelope()
      throws Exception {
    filter.doFilter(
        request,
        response,
        failingWith(
            new AuthenticationServiceException(
                "no se pudo", new IdentityProviderUnavailableException("sin Clavaris"))));

    assertThat(response.getStatus()).isEqualTo(503);
    assertThat(response.getHeader("Retry-After")).isEqualTo("10");
    assertThat(response.getContentAsString()).contains("IDENTITY_PROVIDER_UNAVAILABLE");
  }

  @Test
  void aKeySetThatCouldNotBeFetchedIsTreatedTheSameWay() throws Exception {
    filter.doFilter(
        request,
        response,
        failingWith(new AuthenticationServiceException("jwks", new JwtException("no responde"))));

    assertThat(response.getStatus()).isEqualTo(503);
  }

  @Test
  void aServiceFailureWithoutACauseIsAlsoA503() throws Exception {
    filter.doFilter(
        request, response, failingWith(new AuthenticationServiceException("sin causa")));

    assertThat(response.getStatus()).isEqualTo(503);
  }

  @Test
  void aRequestThatGoesThroughIsLeftAlone() throws Exception {
    MockFilterChain chain = new MockFilterChain();

    filter.doFilter(request, response, chain);

    assertThat(chain.getRequest()).isNotNull();
    assertThat(response.getStatus()).isEqualTo(200);
    assertThat(response.getHeader("Retry-After")).isNull();
  }

  @Test
  void anAuthenticationFailureOfTheTokenItselfIsNotSwallowedHere() {
    BadCredentialsException badToken = new BadCredentialsException("token malo");

    assertThatThrownBy(() -> filter.doFilter(request, response, failingWith(badToken)))
        .isSameAs(badToken);
  }

  @Test
  void otherServletErrorsPropagateUntouched() {
    IllegalStateException other = new IllegalStateException("otra cosa");

    assertThatThrownBy(() -> filter.doFilter(request, response, failingWith(other)))
        .isSameAs(other);
    assertThat(ServletException.class).isNotNull();
  }

  @Test
  void theWaitComesFromTheConfigurationAndIsNotHardcoded() throws Exception {
    new ApiAvailabilityFilter(Duration.ofSeconds(45))
        .doFilter(request, response, failingWith(new AuthenticationServiceException("x")));

    assertThat(response.getHeader("Retry-After")).isEqualTo("45");
  }

  private ListAppender<ILoggingEvent> captureLog() {
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    ((Logger) LoggerFactory.getLogger(ApiAvailabilityFilter.class)).addAppender(appender);
    return appender;
  }

  @Test
  void theLogNamesTheRootCauseSoOperationsCanTellWhatBrokeWithoutAnyTokenInIt() throws Exception {
    ListAppender<ILoggingEvent> log = captureLog();
    request.addHeader("Authorization", "Bearer secreto.secreto.secreto");

    filter.doFilter(
        request,
        response,
        failingWith(
            new AuthenticationServiceException(
                "no se pudo", new IdentityProviderUnavailableException("sin Clavaris"))));

    assertThat(log.list)
        .singleElement()
        .extracting(ILoggingEvent::getFormattedMessage)
        .isEqualTo(
            "event=api_identity_provider_unavailable cause=IdentityProviderUnavailableException");
  }

  @Test
  void withoutACauseTheLogNamesTheExceptionItself() throws Exception {
    ListAppender<ILoggingEvent> log = captureLog();

    filter.doFilter(
        request, response, failingWith(new AuthenticationServiceException("sin causa")));

    assertThat(log.list)
        .singleElement()
        .extracting(ILoggingEvent::getFormattedMessage)
        .isEqualTo("event=api_identity_provider_unavailable cause=AuthenticationServiceException");
  }
}
