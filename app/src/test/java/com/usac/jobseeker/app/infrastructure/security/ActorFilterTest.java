package com.usac.jobseeker.app.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.usac.jobseeker.auth.application.usecase.provisionaccount.ProvisionAccountCommand;
import com.usac.jobseeker.auth.application.usecase.provisionaccount.ProvisionAccountResult;
import com.usac.jobseeker.auth.application.usecase.provisionaccount.ProvisionAccountUseCase;
import com.usac.jobseeker.auth.application.usecase.resolveactor.ActorResolution;
import com.usac.jobseeker.auth.application.usecase.resolveactor.ActorResolution.Reason;
import com.usac.jobseeker.auth.application.usecase.resolveactor.ResolveActorQuery;
import com.usac.jobseeker.auth.application.usecase.resolveactor.ResolveActorUseCase;
import com.usac.jobseeker.auth.domain.model.DeletionStatus;
import com.usac.jobseeker.common.domain.model.AccountId;
import com.usac.jobseeker.common.domain.model.Actor;
import com.usac.jobseeker.common.domain.model.Capability;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/**
 * El actor se reconstruye desde la base en cada petición y nunca desde el token (ADR-0020,
 * NFR-SEC-03). Se prueba con fakes de los casos de uso: ni Spring ni base de datos.
 */
class ActorFilterTest {

  private final AccountId account = new AccountId(UUID.randomUUID());
  private final List<ResolveActorQuery> resolved = new ArrayList<>();
  private final List<ProvisionAccountCommand> provisioned = new ArrayList<>();

  private ActorResolution first;
  private ActorResolution second;

  private MockHttpServletRequest request;
  private MockHttpServletResponse response;
  private MockFilterChain chain;

  @BeforeEach
  void setUp() {
    request = new MockHttpServletRequest("GET", "/app/home");
    response = new MockHttpServletResponse();
    chain = new MockFilterChain();
    first = new ActorResolution.Resolved(new Actor(account, Set.of(Capability.CANDIDATE)));
    second = first;
    login(account.toString());
  }

  @AfterEach
  void clear() {
    SecurityContextHolder.clearContext();
  }

  private void login(String subject) {
    SecurityContextHolder.getContext()
        .setAuthentication(
            new OAuth2AuthenticationToken(
                new DefaultOAuth2User(AuthorityUtils.NO_AUTHORITIES, Map.of("sub", subject), "sub"),
                List.of(),
                "clavaris"));
  }

  /** El filtro de la sesión web: solo actúa con una sesión OIDC y deja salir siempre. */
  private ActorFilter filter() {
    return filterFor(OAuth2AuthenticationToken.class::isInstance, Set.of("/app/logout"));
  }

  /** El filtro de la API: solo actúa con un Bearer y no tiene rutas siempre permitidas. */
  private ActorFilter apiFilter() {
    return filterFor(JwtAuthenticationToken.class::isInstance, Set.of());
  }

  private ActorFilter filterFor(
      Predicate<Authentication> appliesTo, Set<String> alwaysAllowedPaths) {
    ResolveActorUseCase resolve =
        query -> {
          resolved.add(query);
          return resolved.size() == 1 ? first : second;
        };
    ProvisionAccountUseCase provision =
        command -> {
          provisioned.add(command);
          return new ProvisionAccountResult(
              new Actor(command.accountId(), Set.of(Capability.CANDIDATE)),
              DeletionStatus.ACTIVE,
              true);
        };
    return new ActorFilter(resolve, provision, appliesTo, alwaysAllowedPaths);
  }

  private void run() throws Exception {
    filter().doFilter(request, response, chain);
  }

  @Test
  void anActiveAccountContinuesWithItsActorOnTheRequest() throws Exception {
    run();

    assertThat(chain.getRequest()).isNotNull();
    assertThat(CurrentActor.from(request))
        .contains(new Actor(account, Set.of(Capability.CANDIDATE)));
    assertThat(provisioned).isEmpty();
  }

  @Test
  void theCapabilitiesComeFromTheLocalAccountAndNotFromTheTokenClaims() throws Exception {
    first = new ActorResolution.Resolved(new Actor(account, Set.of(Capability.RECRUITER)));
    login(account.toString());

    run();

    assertThat(CurrentActor.from(request).orElseThrow().capabilities())
        .containsExactly(Capability.RECRUITER);
  }

  @Test
  void theAccountIsLookedUpByTheSubjectOfTheSession() throws Exception {
    run();

    assertThat(resolved).containsExactly(new ResolveActorQuery(account));
  }

  @Test
  void anAccountInItsGracePeriodIsRejectedWith403AndItsOwnCode() throws Exception {
    first = new ActorResolution.Rejected(Reason.PENDING_DELETION);

    run();

    assertThat(response.getStatus()).isEqualTo(403);
    assertThat(response.getContentAsString()).contains("ACCOUNT_PENDING_DELETION");
    assertThat(chain.getRequest()).as("no debe continuar").isNull();
    assertThat(CurrentActor.from(request)).isEmpty();
  }

  @Test
  void aDeletedIdentityIsRejectedWith403AndTheUnavailableCode() throws Exception {
    first = new ActorResolution.Rejected(Reason.IDENTITY_DELETED);

    run();

    assertThat(response.getStatus()).isEqualTo(403);
    assertThat(response.getContentAsString()).contains("ACCOUNT_UNAVAILABLE");
    assertThat(chain.getRequest()).isNull();
  }

  @Test
  void anAnonymizedAccountIsRejectedWith403AndTheUnavailableCode() throws Exception {
    first = new ActorResolution.Rejected(Reason.ANONYMIZED);

    run();

    assertThat(response.getStatus()).isEqualTo(403);
    assertThat(response.getContentAsString()).contains("ACCOUNT_UNAVAILABLE");
    assertThat(chain.getRequest()).isNull();
  }

  @Test
  void aSessionOlderThanItsAccountProvisionsItOnceAndThenContinues() throws Exception {
    first = new ActorResolution.Rejected(Reason.NOT_PROVISIONED);
    second = new ActorResolution.Resolved(new Actor(account, Set.of(Capability.CANDIDATE)));

    run();

    assertThat(provisioned).containsExactly(new ProvisionAccountCommand(account));
    assertThat(resolved).as("se vuelve a resolver tras crear la cuenta").hasSize(2);
    assertThat(chain.getRequest()).isNotNull();
    assertThat(CurrentActor.from(request)).isPresent();
  }

  @Test
  void anAccountThatIsStillNotProvisionedAfterProvisioningIsRejectedNotLoopedOn() throws Exception {
    first = new ActorResolution.Rejected(Reason.NOT_PROVISIONED);
    second = new ActorResolution.Rejected(Reason.NOT_PROVISIONED);

    run();

    assertThat(provisioned).hasSize(1);
    assertThat(response.getStatus()).isEqualTo(403);
    assertThat(response.getContentAsString()).contains("ACCOUNT_UNAVAILABLE");
    assertThat(chain.getRequest()).isNull();
  }

  @Test
  void otherRejectionsNeverTriggerProvisioning() throws Exception {
    first = new ActorResolution.Rejected(Reason.PENDING_DELETION);

    run();

    assertThat(provisioned).isEmpty();
    assertThat(resolved).hasSize(1);
  }

  @Test
  void aSubjectThatIsNotAUuidIsRejectedBeforeTouchingTheDatabase() throws Exception {
    login("1-1-1-1-1");

    run();

    assertThat(response.getStatus()).isEqualTo(403);
    assertThat(response.getContentAsString()).contains("IDENTITY_INVALID");
    assertThat(resolved).isEmpty();
    assertThat(chain.getRequest()).isNull();
  }

  @Test
  void logoutIsAllowedInAnyAccountStateWithoutResolvingTheActor() throws Exception {
    first = new ActorResolution.Rejected(Reason.PENDING_DELETION);
    request = new MockHttpServletRequest("POST", "/app/logout");

    run();

    assertThat(chain.getRequest()).isNotNull();
    assertThat(resolved).isEmpty();
  }

  @Test
  void aRequestThatIsNotAnOidcSessionIsLeftAlone() throws Exception {
    SecurityContextHolder.clearContext();

    run();

    assertThat(chain.getRequest()).isNotNull();
    assertThat(resolved).isEmpty();
    assertThat(CurrentActor.from(request)).isEmpty();
  }

  // ---- el mismo filtro al servicio de la API (Bearer) ----

  private void bearerLogin(String subject) {
    Jwt jwt = Jwt.withTokenValue("token").header("alg", "RS256").subject(subject).build();
    SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
  }

  @Test
  void aBearerResolvesItsActorFromTheLocalAccountExactlyLikeAWebSession() throws Exception {
    first = new ActorResolution.Resolved(new Actor(account, Set.of(Capability.RECRUITER)));
    bearerLogin(account.toString());
    request = new MockHttpServletRequest("GET", "/api/v1/algo");

    apiFilter().doFilter(request, response, chain);

    assertThat(resolved).containsExactly(new ResolveActorQuery(account));
    assertThat(CurrentActor.from(request).orElseThrow().capabilities())
        .containsExactly(Capability.RECRUITER);
    assertThat(chain.getRequest()).isNotNull();
  }

  @Test
  void aBearerForAnAccountInItsGracePeriodIsRejectedWith403() throws Exception {
    first = new ActorResolution.Rejected(Reason.PENDING_DELETION);
    bearerLogin(account.toString());
    request = new MockHttpServletRequest("GET", "/api/v1/algo");

    apiFilter().doFilter(request, response, chain);

    assertThat(response.getStatus()).isEqualTo(403);
    assertThat(response.getContentAsString()).contains("ACCOUNT_PENDING_DELETION");
    assertThat(chain.getRequest()).isNull();
  }

  @Test
  void aBearerForAnAccountNeverSeenBeforeIsProvisionedLazilyLikeAFirstLogin() throws Exception {
    first = new ActorResolution.Rejected(Reason.NOT_PROVISIONED);
    second = new ActorResolution.Resolved(new Actor(account, Set.of(Capability.CANDIDATE)));
    bearerLogin(account.toString());
    request = new MockHttpServletRequest("GET", "/api/v1/algo");

    apiFilter().doFilter(request, response, chain);

    assertThat(provisioned).containsExactly(new ProvisionAccountCommand(account));
    assertThat(chain.getRequest()).isNotNull();
  }

  @Test
  void theApiFilterIgnoresAWebSessionAndTheWebFilterIgnoresABearer() throws Exception {
    request = new MockHttpServletRequest("GET", "/api/v1/algo");
    apiFilter().doFilter(request, response, new MockFilterChain()); // sesión web, filtro de la API
    assertThat(resolved).isEmpty();

    bearerLogin(account.toString());
    filter().doFilter(request, new MockHttpServletResponse(), new MockFilterChain());
    assertThat(resolved).as("Bearer, filtro de la web").isEmpty();
  }

  @Test
  void onTheApiNoPathIsExemptSoEvenALogoutLookingPathIsResolved() throws Exception {
    first = new ActorResolution.Rejected(Reason.PENDING_DELETION);
    bearerLogin(account.toString());
    request = new MockHttpServletRequest("POST", "/app/logout");

    apiFilter().doFilter(request, response, chain);

    assertThat(resolved).hasSize(1);
    assertThat(response.getStatus()).isEqualTo(403);
  }
}
