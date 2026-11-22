package com.usac.jobseeker.app.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.usac.jobseeker.auth.application.usecase.provisionaccount.ProvisionAccountCommand;
import com.usac.jobseeker.auth.application.usecase.provisionaccount.ProvisionAccountResult;
import com.usac.jobseeker.auth.application.usecase.provisionaccount.ProvisionAccountUseCase;
import com.usac.jobseeker.auth.domain.model.DeletionStatus;
import com.usac.jobseeker.common.domain.model.AccountId;
import com.usac.jobseeker.common.domain.model.Actor;
import com.usac.jobseeker.common.domain.model.Capability;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;

/**
 * Al terminar el login se crea la cuenta local si es la primera vez (BR-ACCT-09) y se sigue hacia
 * la página pedida. Un `sub` que no es un UUID no puede venir de Clavaris y corta la sesión.
 */
class AccountProvisioningSuccessHandlerTest {

  private final List<ProvisionAccountCommand> provisioned = new ArrayList<>();
  private final ProvisionAccountUseCase provision =
      command -> {
        provisioned.add(command);
        return new ProvisionAccountResult(
            new Actor(command.accountId(), Set.of(Capability.CANDIDATE)),
            DeletionStatus.ACTIVE,
            true);
      };
  private final AccountProvisioningSuccessHandler handler =
      new AccountProvisioningSuccessHandler(provision);

  private static Authentication loginOf(String subject) {
    return new OAuth2AuthenticationToken(
        new DefaultOAuth2User(AuthorityUtils.NO_AUTHORITIES, Map.of("sub", subject), "sub"),
        List.of(),
        "clavaris");
  }

  @Test
  void aValidLoginProvisionsTheAccountOfTheSubjectAndGoesToTheHome() throws Exception {
    AccountId id = new AccountId(UUID.randomUUID());
    MockHttpServletResponse response = new MockHttpServletResponse();

    handler.onAuthenticationSuccess(new MockHttpServletRequest(), response, loginOf(id.toString()));

    assertThat(provisioned).containsExactly(new ProvisionAccountCommand(id));
    assertThat(response.getRedirectedUrl()).isEqualTo("/app/home");
  }

  @Test
  void aSubjectThatIsNotAUuidCutsTheSessionAndProvisionsNothing() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest();
    MockHttpSession session = new MockHttpSession();
    request.setSession(session);
    MockHttpServletResponse response = new MockHttpServletResponse();

    handler.onAuthenticationSuccess(request, response, loginOf("auth0|no-es-de-clavaris"));

    assertThat(session.isInvalid()).isTrue();
    assertThat(provisioned).isEmpty();
    assertThat(response.getStatus()).isEqualTo(403);
    assertThat(response.getContentAsString()).contains("IDENTITY_INVALID");
    assertThat(response.getRedirectedUrl()).isNull();
  }

  @Test
  void theResponseOfARejectedLoginNeverEchoesTheSubject() throws Exception {
    MockHttpServletResponse response = new MockHttpServletResponse();

    handler.onAuthenticationSuccess(
        new MockHttpServletRequest(), response, loginOf("secreto-que-no-debe-salir"));

    assertThat(response.getContentAsString()).doesNotContain("secreto-que-no-debe-salir");
  }
}
