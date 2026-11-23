package com.usac.jobseeker.auth.infrastructure.config;

import com.usac.jobseeker.auth.application.usecase.handleclavariswebhook.HandleClavarisWebhookService;
import com.usac.jobseeker.auth.application.usecase.handleclavariswebhook.HandleClavarisWebhookUseCase;
import com.usac.jobseeker.auth.application.usecase.provisionaccount.ProvisionAccountService;
import com.usac.jobseeker.auth.application.usecase.provisionaccount.ProvisionAccountUseCase;
import com.usac.jobseeker.auth.application.usecase.resolveactor.ResolveActorService;
import com.usac.jobseeker.auth.application.usecase.resolveactor.ResolveActorUseCase;
import com.usac.jobseeker.auth.infrastructure.adapter.out.persistence.JdbcAccountRepository;
import com.usac.jobseeker.auth.infrastructure.adapter.out.persistence.JdbcIdentityStateRepository;
import com.usac.jobseeker.common.application.idempotency.ProcessedEventsPort;
import com.usac.jobseeker.common.application.outbox.DomainEventPublisherPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Cablea los casos de uso de `auth` como beans: `application` no lleva estereotipos (ADR-0015). */
@Configuration(proxyBeanMethods = false)
class AuthConfiguration {

  @Bean
  JdbcAccountRepository jdbcAccountRepository(JdbcClient jdbc) {
    return new JdbcAccountRepository(jdbc);
  }

  @Bean
  ProvisionAccountUseCase provisionAccountUseCase(JdbcAccountRepository repository) {
    return new ProvisionAccountService(repository);
  }

  @Bean
  JdbcIdentityStateRepository jdbcIdentityStateRepository(JdbcClient jdbc) {
    return new JdbcIdentityStateRepository(jdbc);
  }

  // El registro de eventos procesados y la bandeja los aporta `app` (ADR-0016): este módulo solo
  // conoce sus puertos.
  @Bean
  HandleClavarisWebhookUseCase handleClavarisWebhookUseCase(
      ProcessedEventsPort processedEvents,
      JdbcIdentityStateRepository identity,
      DomainEventPublisherPort publisher) {
    return new HandleClavarisWebhookService(processedEvents, identity, publisher);
  }

  @Bean
  ResolveActorUseCase resolveActorUseCase(JdbcAccountRepository repository) {
    return new ResolveActorService(repository);
  }
}
