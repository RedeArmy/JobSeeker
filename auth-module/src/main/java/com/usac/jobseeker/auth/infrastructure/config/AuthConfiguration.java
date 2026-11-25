package com.usac.jobseeker.auth.infrastructure.config;

import com.usac.jobseeker.auth.application.usecase.changerecruiterverification.ChangeRecruiterVerificationService;
import com.usac.jobseeker.auth.application.usecase.changerecruiterverification.ChangeRecruiterVerificationUseCase;
import com.usac.jobseeker.auth.application.usecase.getmyaccount.GetMyAccountService;
import com.usac.jobseeker.auth.application.usecase.getmyaccount.GetMyAccountUseCase;
import com.usac.jobseeker.auth.application.usecase.getrecruiterverification.GetRecruiterVerificationService;
import com.usac.jobseeker.auth.application.usecase.getrecruiterverification.GetRecruiterVerificationUseCase;
import com.usac.jobseeker.auth.application.usecase.handleclavariswebhook.HandleClavarisWebhookService;
import com.usac.jobseeker.auth.application.usecase.handleclavariswebhook.HandleClavarisWebhookUseCase;
import com.usac.jobseeker.auth.application.usecase.provisionaccount.ProvisionAccountService;
import com.usac.jobseeker.auth.application.usecase.provisionaccount.ProvisionAccountUseCase;
import com.usac.jobseeker.auth.application.usecase.resolveactor.ResolveActorService;
import com.usac.jobseeker.auth.application.usecase.resolveactor.ResolveActorUseCase;
import com.usac.jobseeker.auth.application.usecase.reviewrecruiter.ReviewRecruiterService;
import com.usac.jobseeker.auth.application.usecase.reviewrecruiter.ReviewRecruiterUseCase;
import com.usac.jobseeker.auth.infrastructure.adapter.out.persistence.JdbcAccountRepository;
import com.usac.jobseeker.auth.infrastructure.adapter.out.persistence.JdbcIdentityStateRepository;
import com.usac.jobseeker.auth.infrastructure.adapter.out.persistence.JdbcRecruiterVerificationRepository;
import com.usac.jobseeker.common.application.account.AccountChangeObserver;
import com.usac.jobseeker.common.application.audit.AuditPort;
import com.usac.jobseeker.common.application.idempotency.ProcessedEventsPort;
import com.usac.jobseeker.common.application.observability.UseCaseMetricsPort;
import com.usac.jobseeker.common.application.outbox.DomainEventPublisherPort;
import com.usac.jobseeker.common.application.security.OperatorPolicyPort;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Cablea los casos de uso de `auth` como beans: `application` no lleva estereotipos (ADR-0015). */
@Configuration(proxyBeanMethods = false)
class AuthConfiguration {

  @Bean
  JdbcAccountRepository jdbcAccountRepository(
      JdbcClient jdbc, ObjectProvider<AccountChangeObserver> observer) {
    // Sin nadie que escuche (otro módulo que use `auth` solo, o una prueba) no hay nada que avisar.
    return new JdbcAccountRepository(
        jdbc, observer.getIfAvailable(() -> AccountChangeObserver.NONE));
  }

  @Bean
  ProvisionAccountUseCase provisionAccountUseCase(JdbcAccountRepository repository) {
    return new ProvisionAccountService(repository);
  }

  @Bean
  JdbcIdentityStateRepository jdbcIdentityStateRepository(
      JdbcClient jdbc, ObjectProvider<AccountChangeObserver> observer) {
    return new JdbcIdentityStateRepository(
        jdbc, observer.getIfAvailable(() -> AccountChangeObserver.NONE));
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
  GetMyAccountUseCase getMyAccountUseCase(
      JdbcAccountRepository repository, UseCaseMetricsPort metrics) {
    return new GetMyAccountService(repository, metrics);
  }

  @Bean
  ResolveActorUseCase resolveActorUseCase(JdbcAccountRepository repository) {
    return new ResolveActorService(repository);
  }

  @Bean
  JdbcRecruiterVerificationRepository jdbcRecruiterVerificationRepository(
      JdbcClient jdbc, ObjectProvider<AccountChangeObserver> observer) {
    return new JdbcRecruiterVerificationRepository(
        jdbc, observer.getIfAvailable(() -> AccountChangeObserver.NONE));
  }

  @Bean
  GetRecruiterVerificationUseCase getRecruiterVerificationUseCase(
      JdbcRecruiterVerificationRepository repository, UseCaseMetricsPort metrics) {
    return new GetRecruiterVerificationService(repository, metrics);
  }

  @Bean
  ChangeRecruiterVerificationUseCase changeRecruiterVerificationUseCase(
      JdbcRecruiterVerificationRepository repository, UseCaseMetricsPort metrics) {
    return new ChangeRecruiterVerificationService(repository, metrics);
  }

  @Bean
  ReviewRecruiterUseCase reviewRecruiterUseCase(
      OperatorPolicyPort operators,
      ChangeRecruiterVerificationUseCase change,
      AuditPort audit,
      UseCaseMetricsPort metrics) {
    return new ReviewRecruiterService(operators, change, audit, metrics);
  }
}
