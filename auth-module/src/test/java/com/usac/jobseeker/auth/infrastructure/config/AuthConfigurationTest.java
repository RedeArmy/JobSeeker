package com.usac.jobseeker.auth.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.usac.jobseeker.auth.application.usecase.provisionaccount.ProvisionAccountCommand;
import com.usac.jobseeker.auth.application.usecase.provisionaccount.ProvisionAccountResult;
import com.usac.jobseeker.auth.application.usecase.provisionaccount.ProvisionAccountUseCase;
import com.usac.jobseeker.auth.application.usecase.resolveactor.ActorResolution;
import com.usac.jobseeker.auth.application.usecase.resolveactor.ResolveActorQuery;
import com.usac.jobseeker.auth.application.usecase.resolveactor.ResolveActorUseCase;
import com.usac.jobseeker.auth.domain.model.IdentityStatus;
import com.usac.jobseeker.auth.infrastructure.AuthPostgres;
import com.usac.jobseeker.auth.infrastructure.adapter.out.persistence.JdbcAccountRepository;
import com.usac.jobseeker.auth.infrastructure.adapter.out.persistence.JdbcIdentityStateRepository;
import com.usac.jobseeker.common.application.account.AccountChangeObserver;
import com.usac.jobseeker.common.domain.model.AccountId;
import com.usac.jobseeker.common.domain.model.Capability;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * La raíz de composición del módulo, sin Spring: se ensamblan los casos de uso con la misma
 * configuración y se recorre un login de punta a punta contra la tabla real. Comprueba el cableado
 * (que ambos casos de uso hablen con el mismo almacén) sin arrancar la aplicación.
 */
class AuthConfigurationTest {

  private final AuthConfiguration configuration = new AuthConfiguration();
  private final JdbcClient jdbc = JdbcClient.create(AuthPostgres.dataSource());
  private final JdbcAccountRepository repository =
      configuration.jdbcAccountRepository(jdbc, providerOf(null));
  private final ProvisionAccountUseCase provision =
      configuration.provisionAccountUseCase(repository);
  private final ResolveActorUseCase resolve = configuration.resolveActorUseCase(repository);

  /** El proveedor que Spring inyectaría: con un observador registrado o sin ninguno. */
  private static ObjectProvider<AccountChangeObserver> providerOf(AccountChangeObserver observer) {
    StaticListableBeanFactory beans = new StaticListableBeanFactory();
    if (observer != null) {
      beans.addBean("observer", observer);
    }
    return beans.getBeanProvider(AccountChangeObserver.class);
  }

  @Test
  void theAssembledUseCasesShareTheSameStoreFromFirstLoginToResolution() {
    AccountId id = new AccountId(UUID.randomUUID());

    ProvisionAccountResult firstLogin = provision.execute(new ProvisionAccountCommand(id));
    ActorResolution resolution = resolve.execute(new ResolveActorQuery(id));

    assertThat(firstLogin.created()).isTrue();
    assertThat(resolution)
        .isInstanceOfSatisfying(
            ActorResolution.Resolved.class,
            resolved -> {
              assertThat(resolved.actor().id()).isEqualTo(id);
              assertThat(resolved.actor().capabilities()).containsExactly(Capability.CANDIDATE);
            });
  }

  @Test
  void anAccountThatWasNeverProvisionedResolvesAsNotProvisioned() {
    AccountId unknown = new AccountId(UUID.randomUUID());

    assertThat(resolve.execute(new ResolveActorQuery(unknown)))
        .isEqualTo(new ActorResolution.Rejected(ActorResolution.Reason.NOT_PROVISIONED));
  }

  // El aviso que invalida la memoria por petición: lo da el adaptador que escribe, no cada caso de
  // uso.
  @Test
  void theWiredObserverIsToldWhenAnAccountIsProvisionedOrItsIdentityChanges() {
    List<AccountId> told = new ArrayList<>();
    AccountId id = new AccountId(UUID.randomUUID());
    JdbcAccountRepository observed =
        configuration.jdbcAccountRepository(jdbc, providerOf(told::add));
    JdbcIdentityStateRepository identity =
        configuration.jdbcIdentityStateRepository(jdbc, providerOf(told::add));

    configuration.provisionAccountUseCase(observed).execute(new ProvisionAccountCommand(id));
    configuration.provisionAccountUseCase(observed).execute(new ProvisionAccountCommand(id));
    identity.applyIdentityEvent(id, IdentityStatus.SUSPENDED, Instant.now(), UUID.randomUUID());

    // La segunda aprovisión no escribe nada y no avisa; las dos escrituras reales, sí.
    assertThat(told).containsExactly(id, id);
  }

  @Test
  void withoutAnObserverTheAdaptersStillWork() {
    AccountId id = new AccountId(UUID.randomUUID());

    assertThat(configuration.jdbcIdentityStateRepository(jdbc, providerOf(null)).accountExists(id))
        .isFalse();
  }
}
