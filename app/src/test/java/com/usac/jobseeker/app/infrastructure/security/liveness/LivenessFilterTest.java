package com.usac.jobseeker.app.infrastructure.security.liveness;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.usac.jobseeker.app.infrastructure.security.liveness.LivenessCoordinator.Verdict;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;

class LivenessFilterTest {

  private final LivenessCoordinator coordinator = mock(LivenessCoordinator.class);
  private final LivenessStore store = mock(LivenessStore.class);
  private final LivenessFilter filter = new LivenessFilter(coordinator, store);

  private MockHttpSession session;
  private MockHttpServletRequest request;
  private MockFilterChain chain;

  @BeforeEach
  void setUp() {
    session = new MockHttpSession();
    request = new MockHttpServletRequest("GET", "/app/home");
    request.setSession(session);
    chain = new MockFilterChain();
    SecurityContextHolder.getContext()
        .setAuthentication(
            new OAuth2AuthenticationToken(
                new DefaultOAuth2User(
                    AuthorityUtils.NO_AUTHORITIES, Map.of("sub", "cuenta"), "sub"),
                List.of(),
                "clavaris"));
  }

  @AfterEach
  void clear() {
    SecurityContextHolder.clearContext();
  }

  private void run() throws Exception {
    filter.doFilter(request, new MockHttpServletResponse(), chain);
  }

  @Test
  void aLiveSessionContinuesWithItsAuthentication() throws Exception {
    session.setAttribute(VaultingAuthorizedClientRepository.LINK_ATTRIBUTE, "enlace");
    when(coordinator.verify("enlace")).thenReturn(Verdict.ALIVE);

    run();

    assertThat(chain.getRequest()).isNotNull();
    assertThat(SecurityContextHolder.getContext().getAuthentication()).isNotNull();
    assertThat(session.isInvalid()).isFalse();
  }

  @Test
  void aClosedSessionIsInvalidatedItsContextClearedAndItsStateDeleted() throws Exception {
    session.setAttribute(VaultingAuthorizedClientRepository.LINK_ATTRIBUTE, "enlace");
    when(coordinator.verify("enlace")).thenReturn(Verdict.CLOSED);

    run();

    assertThat(session.isInvalid()).isTrue();
    assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    verify(store).delete("enlace");
    assertThat(chain.getRequest()).as("la petición sigue, ya sin identidad").isNotNull();
  }

  @Test
  void anAuthenticatedSessionWithoutALivenessLinkIsClosed() throws Exception {
    run();

    assertThat(session.isInvalid()).isTrue();
    assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    verify(coordinator, never()).verify(anyString());
  }

  @Test
  void logoutAndStaticResourcesAreNotChecked() throws Exception {
    for (String path :
        List.of("/app/logout", "/css/app.css", "/js/htmx.min.js", "/actuator/health")) {
      request = new MockHttpServletRequest("GET", path);
      request.setSession(session);
      chain = new MockFilterChain();
      run();
    }

    assertThat(session.isInvalid()).isFalse();
    verify(coordinator, never()).verify(anyString());
  }

  @Test
  void anAnonymousRequestIsLeftAlone() throws Exception {
    SecurityContextHolder.clearContext();

    run();

    verify(coordinator, never()).verify(anyString());
    assertThat(chain.getRequest()).isNotNull();
  }

  @Test
  void aRedisFailureDoesNotCloseEveryoneOut() throws Exception {
    session.setAttribute(VaultingAuthorizedClientRepository.LINK_ATTRIBUTE, "enlace");
    when(coordinator.verify("enlace")).thenThrow(new QueryTimeoutException("redis"));

    run();

    assertThat(session.isInvalid()).isFalse();
    assertThat(chain.getRequest()).isNotNull();
  }
}
