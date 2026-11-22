package com.usac.jobseeker.app.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;

/**
 * Sin Clavaris el inicio de sesión responde 503 y no un 500 (ADR-0020, degradación), y solo los dos
 * pasos del login lo consultan: el resto de la aplicación no depende de él.
 */
class IdentityProviderAvailabilityFilterTest {

  private static final ClientRegistration REGISTRATION =
      ClientRegistration.withRegistrationId("clavaris")
          .clientId("c")
          .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
          .redirectUri("http://localhost/cb")
          .authorizationUri("http://localhost/a")
          .tokenUri("http://localhost/t")
          .build();

  private final AtomicInteger lookups = new AtomicInteger();

  private IdentityProviderAvailabilityFilter filter(boolean available) {
    ClientRegistrationRepository clients =
        id -> {
          lookups.incrementAndGet();
          return available ? REGISTRATION : null;
        };
    return new IdentityProviderAvailabilityFilter(clients, Duration.ofSeconds(10));
  }

  private MockHttpServletResponse run(
      IdentityProviderAvailabilityFilter filter, String path, String accept) throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
    if (accept != null) {
      request.addHeader("Accept", accept);
    }
    MockHttpServletResponse response = new MockHttpServletResponse();
    filter.doFilter(request, response, new MockFilterChain());
    return response;
  }

  @Test
  void bothStepsOfTheLoginAreAnsweredWith503WhenClavarisIsNotAvailable() throws Exception {
    for (String path :
        new String[] {"/oauth2/authorization/clavaris", "/login/oauth2/code/clavaris"}) {
      MockHttpServletResponse response = run(filter(false), path, "application/json");

      assertThat(response.getStatus()).as(path).isEqualTo(503);
      assertThat(response.getHeader("Retry-After")).as(path).isEqualTo("10");
    }
  }

  @Test
  void aBrowserGetsAReadablePageWithoutInlineScriptsOrStylesSoTheStrictCspHolds() throws Exception {
    MockHttpServletResponse response =
        run(filter(false), "/oauth2/authorization/clavaris", "text/html,application/xhtml+xml");

    assertThat(response.getContentType()).startsWith("text/html");
    assertThat(response.getContentAsString())
        .contains("No podemos iniciar sesión ahora")
        .doesNotContain("<script")
        .doesNotContain("style=")
        .doesNotContain("<style");
  }

  @Test
  void anApiClientGetsTheStandardEnvelopeWithItsOwnCode() throws Exception {
    MockHttpServletResponse response =
        run(filter(false), "/oauth2/authorization/clavaris", "application/json");

    assertThat(response.getContentType()).startsWith("application/json");
    assertThat(response.getContentAsString()).contains("IDENTITY_PROVIDER_UNAVAILABLE");
  }

  @Test
  void aRequestWithoutAnAcceptHeaderIsTreatedAsAnApiClient() throws Exception {
    MockHttpServletResponse response = run(filter(false), "/oauth2/authorization/clavaris", null);

    assertThat(response.getContentType()).startsWith("application/json");
  }

  @Test
  void theLoginPassesThroughWhenClavarisIsAvailable() throws Exception {
    MockHttpServletResponse response = run(filter(true), "/oauth2/authorization/clavaris", null);

    assertThat(response.getStatus()).isEqualTo(200);
    assertThat(response.getHeader("Retry-After")).isNull();
  }

  @Test
  void everyOtherPathPassesThroughWithoutEvenAskingClavaris() throws Exception {
    for (String path :
        new String[] {"/app/home", "/", "/actuator/health", "/oauth2/authorization/otro"}) {
      MockHttpServletResponse response = run(filter(false), path, null);

      assertThat(response.getStatus()).as(path).isEqualTo(200);
    }
    assertThat(lookups.get()).as("las páginas ya abiertas no dependen de Clavaris").isZero();
  }

  @Test
  void theHtmlPageIsExplicitlyDeclaredAsUtf8SoItsAccentsSurvive() throws Exception {
    MockHttpServletRequest request =
        new MockHttpServletRequest("GET", "/oauth2/authorization/clavaris");
    request.addHeader("Accept", "text/html");
    MockHttpServletResponse response = org.mockito.Mockito.spy(new MockHttpServletResponse());

    filter(false).doFilter(request, response, new MockFilterChain());

    org.mockito.Mockito.verify(response).setCharacterEncoding("UTF-8");
  }
}
