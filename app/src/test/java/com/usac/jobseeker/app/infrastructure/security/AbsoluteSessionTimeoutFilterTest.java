package com.usac.jobseeker.app.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;

class AbsoluteSessionTimeoutFilterTest {

  private static Clock at(Instant instant) {
    return Clock.fixed(instant, ZoneOffset.UTC);
  }

  private boolean sessionSurvives(Duration age) throws Exception {
    // La fecha de creación de MockHttpSession es «ahora»; se mueve el reloj en vez de la sesión.
    MockHttpSession session = new MockHttpSession();
    Instant created = Instant.ofEpochMilli(session.getCreationTime());
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setSession(session);
    new AbsoluteSessionTimeoutFilter(Duration.ofHours(8), at(created.plus(age)))
        .doFilter(request, new MockHttpServletResponse(), new MockFilterChain());
    return !session.isInvalid();
  }

  @Test
  void aSessionWithinTheLimitSurvives() throws Exception {
    assertThat(sessionSurvives(Duration.ofHours(7).plusMinutes(59))).isTrue();
  }

  @Test
  void aSessionPastTheLimitIsInvalidatedEvenIfItWasActive() throws Exception {
    // ADR-0020: ≤ 8 h absolutas, aunque la sesión no haya dejado de usarse.
    assertThat(sessionSurvives(Duration.ofHours(8).plusSeconds(1))).isFalse();
  }

  @Test
  void aRequestWithoutASessionDoesNotCreateOne() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest();
    new AbsoluteSessionTimeoutFilter(Duration.ofHours(8), Clock.systemUTC())
        .doFilter(request, new MockHttpServletResponse(), new MockFilterChain());
    assertThat(request.getSession(false)).isNull();
  }
}
