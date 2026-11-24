package com.usac.jobseeker.app.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.usac.jobseeker.auth.application.usecase.resolveactor.ActorResolution;
import com.usac.jobseeker.auth.application.usecase.resolveactor.ResolveActorQuery;
import com.usac.jobseeker.common.domain.model.AccountId;
import com.usac.jobseeker.common.domain.model.Actor;
import com.usac.jobseeker.common.domain.model.Capability;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * La cuenta se resuelve una vez por petición, solo dentro del ámbito que abre el filtro, y se
 * olvida en cuanto la base de datos cambia.
 */
class RequestScopedActorResolutionTest {

  private final AccountId account = new AccountId(UUID.randomUUID());
  private final AccountId other = new AccountId(UUID.randomUUID());
  private final List<AccountId> asked = new ArrayList<>();
  private ActorResolution answer =
      new ActorResolution.Resolved(new Actor(account, Set.of(Capability.CANDIDATE)));
  private MockHttpServletRequest request;

  private final RequestScopedActorResolution resolution =
      new RequestScopedActorResolution(
          query -> {
            asked.add(query.accountId());
            return answer;
          });

  /** Una petición enlazada al hilo, sin que el filtro haya abierto todavía el ámbito. */
  private void aBoundRequest() {
    request = new MockHttpServletRequest();
    RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
  }

  private void aRequestThroughTheFilter() {
    aBoundRequest();
    ActorResolutionScope.open(request);
  }

  @AfterEach
  void endTheRequest() {
    RequestContextHolder.resetRequestAttributes();
  }

  @Test
  void aResolvedAccountIsQueriedOnceNoMatterHowManyTimesItIsAskedInTheSameRequest() {
    aRequestThroughTheFilter();

    var first = resolution.execute(new ResolveActorQuery(account));
    var second = resolution.execute(new ResolveActorQuery(account));
    var third = resolution.execute(new ResolveActorQuery(account));

    assertThat(asked).containsExactly(account);
    assertThat(second).isSameAs(first);
    assertThat(third).isSameAs(first);
  }

  @Test
  void eachAccountHasItsOwnEntry() {
    aRequestThroughTheFilter();
    answer = new ActorResolution.Resolved(new Actor(other, Set.of()));

    resolution.execute(new ResolveActorQuery(account));
    resolution.execute(new ResolveActorQuery(other));
    resolution.execute(new ResolveActorQuery(account));

    assertThat(asked).containsExactly(account, other);
  }

  @Test
  void nothingIsRememberedFromOneRequestToTheNext() {
    aRequestThroughTheFilter();
    resolution.execute(new ResolveActorQuery(account));
    RequestContextHolder.resetRequestAttributes();
    aRequestThroughTheFilter();

    resolution.execute(new ResolveActorQuery(account));

    assertThat(asked).containsExactly(account, account);
  }

  @Test
  void aRejectedAccountIsNeverRememberedSoAnAccountCreatedByAnotherRequestIsSeen() {
    aRequestThroughTheFilter();
    answer = new ActorResolution.Rejected(ActorResolution.Reason.NOT_PROVISIONED);

    var notProvisioned = resolution.execute(new ResolveActorQuery(account));
    answer = new ActorResolution.Resolved(new Actor(account, Set.of(Capability.CANDIDATE)));
    var provisioned = resolution.execute(new ResolveActorQuery(account));

    assertThat(notProvisioned).isInstanceOf(ActorResolution.Rejected.class);
    assertThat(provisioned).isInstanceOf(ActorResolution.Resolved.class);
    assertThat(asked).containsExactly(account, account);
  }

  // Era el fallo real: Spring Test deja una petición enlazada al hilo durante todo el método de
  // prueba. Sin el ámbito explícito, un cambio en la base de datos entre dos preguntas no se veía.
  @Test
  void aBoundRequestWithoutTheFilterScopeRemembersNothing() {
    aBoundRequest();

    resolution.execute(new ResolveActorQuery(account));
    resolution.execute(new ResolveActorQuery(account));

    assertThat(asked).containsExactly(account, account);
  }

  @Test
  void outsideAnyRequestEveryCallGoesToTheDatabase() {
    resolution.execute(new ResolveActorQuery(account));
    resolution.execute(new ResolveActorQuery(account));

    assertThat(asked).containsExactly(account, account);
  }

  @Test
  void closingTheScopeEndsTheMemory() {
    aRequestThroughTheFilter();
    resolution.execute(new ResolveActorQuery(account));

    ActorResolutionScope.close(request);
    resolution.execute(new ResolveActorQuery(account));

    assertThat(asked).containsExactly(account, account);
  }

  // Un cambio en la cuenta dentro de la petición: lo siguiente ya no ve la cuenta de antes.
  @Test
  void whenTheAccountChangesInTheDatabaseTheNextQuestionSeesTheChange() {
    aRequestThroughTheFilter();
    resolution.execute(new ResolveActorQuery(account));
    answer = new ActorResolution.Rejected(ActorResolution.Reason.PENDING_DELETION);

    ActorResolutionScope.forget(account);
    var afterTheChange = resolution.execute(new ResolveActorQuery(account));

    assertThat(afterTheChange).isEqualTo(answer);
    assertThat(asked).containsExactly(account, account);
  }

  @Test
  void forgettingOneAccountLeavesTheOthersRemembered() {
    aRequestThroughTheFilter();
    answer = new ActorResolution.Resolved(new Actor(other, Set.of()));
    resolution.execute(new ResolveActorQuery(other));
    resolution.execute(new ResolveActorQuery(account));

    ActorResolutionScope.forget(account);
    resolution.execute(new ResolveActorQuery(other));

    assertThat(asked).containsExactly(other, account);
  }

  @Test
  void forgettingWithoutAScopeDoesNothingAndDoesNotFail() {
    ActorResolutionScope.forget(account);
    aBoundRequest();
    ActorResolutionScope.forget(account);

    assertThat(asked).isEmpty();
  }
}
