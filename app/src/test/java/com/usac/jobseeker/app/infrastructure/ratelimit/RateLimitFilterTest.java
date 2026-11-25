package com.usac.jobseeker.app.infrastructure.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import com.usac.jobseeker.app.infrastructure.ratelimit.RateLimitFilter.Stage;
import com.usac.jobseeker.app.infrastructure.ratelimit.RateLimitProperties.KeyKind;
import com.usac.jobseeker.app.infrastructure.ratelimit.RateLimitProperties.Policy;
import com.usac.jobseeker.app.infrastructure.security.CurrentActor;
import com.usac.jobseeker.common.domain.model.AccountId;
import com.usac.jobseeker.common.domain.model.Actor;
import com.usac.jobseeker.common.domain.model.Capability;
import jakarta.servlet.ServletException;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/** El filtro con un limitador falso: en qué punto actúa, con qué clave y qué responde. */
class RateLimitFilterTest {

  private record Call(String policy, String key) {}

  private static final Duration MINUTE = Duration.ofMinutes(1);

  private final List<Call> calls = new ArrayList<>();
  private RateLimitDecision decision = new RateLimitDecision(true, 10, 9, 0);
  private boolean enabled = true;

  private final RateLimiter limiter =
      (name, policy, key) -> {
        calls.add(new Call(name, key));
        return decision;
      };

  private RateLimitFilter filter(Stage stage) {
    Map<String, Policy> policies =
        Map.of(
            "login", new Policy(10, 10, MINUTE, KeyKind.IP),
            "webhook", new Policy(120, 120, MINUTE, KeyKind.IP),
            "write", new Policy(30, 30, MINUTE, KeyKind.ACCOUNT),
            "read", new Policy(120, 120, MINUTE, KeyKind.ACCOUNT),
            "suggestions", new Policy(10, 10, MINUTE, KeyKind.ACCOUNT));
    return new RateLimitFilter(stage, new RateLimitProperties(enabled, policies), limiter);
  }

  private static MockHttpServletRequest request(String method, String uri) {
    MockHttpServletRequest request = new MockHttpServletRequest(method, uri);
    request.setRemoteAddr("203.0.113.7");
    return request;
  }

  private static Actor bind(MockHttpServletRequest request) {
    Actor actor = new Actor(new AccountId(UUID.randomUUID()), Set.of(Capability.CANDIDATE));
    request.setAttribute(CurrentActor.class.getName() + ".ACTOR", actor);
    return actor;
  }

  private MockHttpServletResponse run(
      Stage stage, MockHttpServletRequest request, MockFilterChain chain)
      throws ServletException, IOException {
    MockHttpServletResponse response = new MockHttpServletResponse();
    filter(stage).doFilter(request, response, chain);
    return response;
  }

  // ---- en qué punto de la cadena actúa cada política ----

  @Test
  void anIpPolicyOnlyActsBeforeAuthentication() throws Exception {
    run(Stage.AFTER_AUTH, request("POST", "/api/v1/webhooks/clavaris"), new MockFilterChain());
    assertThat(calls).isEmpty();

    run(Stage.BEFORE_AUTH, request("POST", "/api/v1/webhooks/clavaris"), new MockFilterChain());
    assertThat(calls).containsExactly(new Call("webhook", "203.0.113.7"));
  }

  @Test
  void anAccountPolicyOnlyActsAfterAuthentication() throws Exception {
    run(Stage.BEFORE_AUTH, request("GET", "/api/v1/me"), new MockFilterChain());
    assertThat(calls).isEmpty();

    MockHttpServletRequest request = request("GET", "/api/v1/me");
    Actor actor = bind(request);
    run(Stage.AFTER_AUTH, request, new MockFilterChain());
    assertThat(calls).containsExactly(new Call("read", actor.id().value().toString()));
  }

  @Test
  void aRequestWithoutAnActorFallsBackToItsIp() throws Exception {
    run(Stage.AFTER_AUTH, request("GET", "/app/home"), new MockFilterChain());

    assertThat(calls).containsExactly(new Call("read", "203.0.113.7"));
  }

  @Test
  void theForwardedForHeaderIsIgnoredBecauseAnyoneCanForgeIt() throws Exception {
    MockHttpServletRequest request = request("GET", "/oauth2/authorization/clavaris");
    request.addHeader("X-Forwarded-For", "198.51.100.99");

    run(Stage.BEFORE_AUTH, request, new MockFilterChain());

    assertThat(calls).containsExactly(new Call("login", "203.0.113.7"));
  }

  @Test
  void twoAccountsHaveTheirOwnKeysSoOneCannotConsumeTheOthersLimit() throws Exception {
    MockHttpServletRequest first = request("GET", "/api/v1/me");
    MockHttpServletRequest second = request("GET", "/api/v1/me");
    bind(first);
    bind(second);

    run(Stage.AFTER_AUTH, first, new MockFilterChain());
    run(Stage.AFTER_AUTH, second, new MockFilterChain());

    assertThat(calls).extracting(Call::key).doesNotHaveDuplicates();
  }

  // ---- lo que no se limita ----

  @Test
  void operationalRoutesAndADisabledLimiterNeverCallTheLimiter() throws Exception {
    run(Stage.AFTER_AUTH, request("GET", "/actuator/prometheus"), new MockFilterChain());
    enabled = false;
    run(Stage.AFTER_AUTH, request("GET", "/api/v1/me"), new MockFilterChain());

    assertThat(calls).isEmpty();
  }

  // ---- la respuesta ----

  @Test
  void anAllowedRequestContinuesAndCarriesTheRemainingTokens() throws Exception {
    MockFilterChain chain = new MockFilterChain();
    MockHttpServletRequest request = request("GET", "/api/v1/me");
    bind(request);

    MockHttpServletResponse response = run(Stage.AFTER_AUTH, request, chain);

    assertThat(chain.getRequest()).isNotNull();
    assertThat(response.getStatus()).isEqualTo(200);
    assertThat(response.getHeader("X-RateLimit-Limit")).isEqualTo("10");
    assertThat(response.getHeader("X-RateLimit-Remaining")).isEqualTo("9");
    assertThat(response.getHeader("Retry-After")).isNull();
  }

  @Test
  void aLimitedRequestIsRejectedWith429TheStandardEnvelopeAndRetryAfter() throws Exception {
    decision = new RateLimitDecision(false, 10, 0, 17);
    MockFilterChain chain = new MockFilterChain();
    MockHttpServletRequest request = request("GET", "/api/v1/me");
    bind(request);

    MockHttpServletResponse response = run(Stage.AFTER_AUTH, request, chain);

    assertThat(chain.getRequest()).as("no llega al resto de la cadena").isNull();
    assertThat(response.getStatus()).isEqualTo(429);
    assertThat(response.getHeader("Retry-After")).isEqualTo("17");
    assertThat(response.getHeader("X-RateLimit-Limit")).isEqualTo("10");
    assertThat(response.getHeader("X-RateLimit-Remaining")).isEqualTo("0");
    assertThat(response.getContentAsString())
        .contains("\"code\":\"RATE_LIMIT_EXCEEDED\"", "\"traceId\"", "\"timestamp\"");
  }

  @Test
  void twoInstancesInOneChainBothRunBecauseTheirFilteredMarkerDiffers() {
    assertThat(filter(Stage.BEFORE_AUTH).getAlreadyFilteredAttributeName())
        .isNotEqualTo(filter(Stage.AFTER_AUTH).getAlreadyFilteredAttributeName());
  }
}
