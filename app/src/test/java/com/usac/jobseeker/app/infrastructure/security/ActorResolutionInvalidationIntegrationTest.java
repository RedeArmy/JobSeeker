package com.usac.jobseeker.app.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.usac.jobseeker.app.InfrastructureTestConfiguration;
import com.usac.jobseeker.auth.application.usecase.provisionaccount.ProvisionAccountCommand;
import com.usac.jobseeker.auth.application.usecase.provisionaccount.ProvisionAccountUseCase;
import com.usac.jobseeker.auth.application.usecase.resolveactor.ActorResolution;
import com.usac.jobseeker.auth.application.usecase.resolveactor.ResolveActorQuery;
import com.usac.jobseeker.auth.application.usecase.resolveactor.ResolveActorUseCase;
import com.usac.jobseeker.auth.domain.model.IdentityStatus;
import com.usac.jobseeker.auth.infrastructure.adapter.out.persistence.JdbcIdentityStateRepository;
import com.usac.jobseeker.common.domain.model.AccountId;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Lo que la memoria por petición garantiza de punta a punta, con los beans reales: un cambio de la
 * cuenta que escribe el adaptador de `auth` llega al ámbito de la petición sin que ningún caso de
 * uso tenga que acordarse de avisar. Es el fallo que apareció al introducirla.
 */
@SpringBootTest
@Import(InfrastructureTestConfiguration.class)
class ActorResolutionInvalidationIntegrationTest {

  @Autowired private ProvisionAccountUseCase provision;
  @Autowired private ResolveActorUseCase resolve;
  @Autowired private JdbcIdentityStateRepository identity;
  @Autowired private JdbcTemplate jdbc;

  private MockHttpServletRequest request;

  private void aRequestThroughTheFilter() {
    request = new MockHttpServletRequest();
    RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    ActorResolutionScope.open(request);
  }

  @AfterEach
  void endTheRequest() {
    RequestContextHolder.resetRequestAttributes();
  }

  @Test
  void aChangeWrittenByTheAuthAdapterIsSeenByTheNextQuestionOfTheSameRequest() {
    AccountId id = new AccountId(UUID.randomUUID());
    provision.execute(new ProvisionAccountCommand(id));
    aRequestThroughTheFilter();
    assertThat(resolve.execute(new ResolveActorQuery(id)))
        .isInstanceOf(ActorResolution.Resolved.class);

    identity.applyIdentityEvent(id, IdentityStatus.DELETED, Instant.now(), UUID.randomUUID());

    assertThat(resolve.execute(new ResolveActorQuery(id)))
        .isEqualTo(new ActorResolution.Rejected(ActorResolution.Reason.IDENTITY_DELETED));
  }

  @Test
  void provisioningAnAccountInsideTheRequestIsSeenByTheNextQuestion() {
    AccountId id = new AccountId(UUID.randomUUID());
    aRequestThroughTheFilter();
    assertThat(resolve.execute(new ResolveActorQuery(id)))
        .isEqualTo(new ActorResolution.Rejected(ActorResolution.Reason.NOT_PROVISIONED));

    provision.execute(new ProvisionAccountCommand(id));

    assertThat(resolve.execute(new ResolveActorQuery(id)))
        .isInstanceOf(ActorResolution.Resolved.class);
  }

  // La carrera del primer acceso: otra petición crea la cuenta entre las dos preguntas de esta, así
  // que `insertIfAbsent` de esta no escribe y nadie avisa. Si se recordara el rechazo, esta
  // petición
  // seguiría viendo una cuenta que no existe y respondería 403 a alguien que acaba de ser creado.
  @Test
  void
      anAccountCreatedByAnotherRequestBetweenTwoQuestionsIsSeenBecauseRejectionsAreNotRemembered() {
    AccountId id = new AccountId(UUID.randomUUID());
    aRequestThroughTheFilter();
    assertThat(resolve.execute(new ResolveActorQuery(id)))
        .isEqualTo(new ActorResolution.Rejected(ActorResolution.Reason.NOT_PROVISIONED));

    jdbc.update("insert into accounts (id, is_candidate_enabled) values (?, true)", id.value());

    assertThat(resolve.execute(new ResolveActorQuery(id)))
        .isInstanceOf(ActorResolution.Resolved.class);
  }
}
