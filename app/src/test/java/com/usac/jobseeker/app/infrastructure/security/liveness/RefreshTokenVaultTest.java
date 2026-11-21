package com.usac.jobseeker.app.infrastructure.security.liveness;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2RefreshToken;

/** El almacén de clientes autorizados y el cierre de sesión con revocación (ADR-0020, regla 3). */
class RefreshTokenVaultTest {

  private final LivenessStore store = mock(LivenessStore.class);
  private final RefreshTokenClient clavaris = mock(RefreshTokenClient.class);
  private final RefreshTokenCipher cipher =
      RefreshTokenCipher.from(
          new EncryptionProperties("k1", "k1:" + Base64.getEncoder().encodeToString(new byte[32])));
  private final VaultingAuthorizedClientRepository vault =
      new VaultingAuthorizedClientRepository(store, cipher, Clock.systemUTC());

  private static OAuth2AuthorizedClient clientWith(String refreshToken) {
    ClientRegistration registration =
        ClientRegistration.withRegistrationId("clavaris")
            .clientId("c")
            .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
            .redirectUri("http://localhost/cb")
            .authorizationUri("http://localhost/a")
            .tokenUri("http://localhost/t")
            .build();
    Instant now = Instant.now();
    return new OAuth2AuthorizedClient(
        registration,
        "cuenta",
        new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER, "a", now, now.plusSeconds(300)),
        refreshToken == null ? null : new OAuth2RefreshToken(refreshToken, now));
  }

  @Test
  void theRefreshTokenGoesToTheStoreAndTheSessionOnlyKeepsTheLinkId() {
    when(store.begin(anyString(), any())).thenReturn("enlace-nuevo");
    MockHttpServletRequest request = new MockHttpServletRequest();

    vault.saveAuthorizedClient(
        clientWith("el-refresh-token"), null, request, new MockHttpServletResponse());

    verify(store).begin(anyString(), any());
    MockHttpSession session = (MockHttpSession) request.getSession();
    assertThat(session.getAttributeNames().asIterator())
        .toIterable()
        .containsExactly(VaultingAuthorizedClientRepository.LINK_ATTRIBUTE);
    assertThat(session.getAttribute(VaultingAuthorizedClientRepository.LINK_ATTRIBUTE))
        .isEqualTo("enlace-nuevo");
  }

  @Test
  void aLoginOverASessionThatAlreadyHadALinkDoesNotLeaveItOrphaned() {
    when(store.begin(anyString(), any())).thenReturn("enlace-nuevo");
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.getSession().setAttribute(VaultingAuthorizedClientRepository.LINK_ATTRIBUTE, "viejo");

    vault.saveAuthorizedClient(clientWith("t"), null, request, new MockHttpServletResponse());

    verify(store).delete("viejo");
  }

  @Test
  void aLoginWithoutARefreshTokenIsRejected() {
    assertThatThrownBy(
            () ->
                vault.saveAuthorizedClient(
                    clientWith(null),
                    null,
                    new MockHttpServletRequest(),
                    new MockHttpServletResponse()))
        .isInstanceOf(OAuth2AuthenticationException.class)
        .extracting(e -> ((OAuth2AuthenticationException) e).getError().getErrorCode())
        .isEqualTo("refresh_token_missing");
    verify(store, never()).begin(anyString(), any());
  }

  @Test
  void aLoginWithoutAnEncryptionKeyIsRejectedInsteadOfStoringInThePlain() {
    VaultingAuthorizedClientRepository withoutKey =
        new VaultingAuthorizedClientRepository(
            store,
            RefreshTokenCipher.from(new EncryptionProperties(null, null)),
            Clock.systemUTC());

    assertThatThrownBy(
            () ->
                withoutKey.saveAuthorizedClient(
                    clientWith("t"),
                    null,
                    new MockHttpServletRequest(),
                    new MockHttpServletResponse()))
        .extracting(e -> ((OAuth2AuthenticationException) e).getError().getErrorCode())
        .isEqualTo("encryption_not_configured");
    verify(store, never()).begin(anyString(), any());
  }

  @Test
  void nothingIsEverLoadedBackFromTheSession() {
    assertThat((Object) vault.loadAuthorizedClient("clavaris", null, new MockHttpServletRequest()))
        .isNull();
  }

  @Test
  void logoutRevokesTheTokenInClavarisAndDeletesTheState() {
    LivenessState state = new LivenessState("cifrado", Instant.now(), Instant.EPOCH);
    when(store.find("enlace")).thenReturn(Optional.of(state));
    when(store.refreshTokenOf("enlace", state)).thenReturn("rt-claro");
    when(clavaris.revoke("rt-claro")).thenReturn(true);
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.getSession().setAttribute(VaultingAuthorizedClientRepository.LINK_ATTRIBUTE, "enlace");

    new RevokingLogoutHandler(store, clavaris).logout(request, new MockHttpServletResponse(), null);

    verify(clavaris).revoke("rt-claro");
    verify(store).delete("enlace");
  }

  @Test
  void logoutStillCompletesWhenClavarisCannotBeReached() {
    LivenessState state = new LivenessState("cifrado", Instant.now(), Instant.EPOCH);
    when(store.find("enlace")).thenReturn(Optional.of(state));
    when(store.refreshTokenOf("enlace", state)).thenReturn("rt-claro");
    when(clavaris.revoke("rt-claro")).thenThrow(new IllegalStateException("caído"));
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.getSession().setAttribute(VaultingAuthorizedClientRepository.LINK_ATTRIBUTE, "enlace");

    new RevokingLogoutHandler(store, clavaris).logout(request, new MockHttpServletResponse(), null);

    verify(store).delete("enlace");
  }

  @Test
  void logoutWithoutALinkDoesNothing() {
    new RevokingLogoutHandler(store, clavaris)
        .logout(new MockHttpServletRequest(), new MockHttpServletResponse(), null);

    verify(clavaris, never()).revoke(anyString());
  }
}
