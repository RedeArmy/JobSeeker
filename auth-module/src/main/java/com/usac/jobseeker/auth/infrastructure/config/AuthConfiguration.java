package com.usac.jobseeker.auth.infrastructure.config;

import com.usac.jobseeker.auth.application.usecase.provisionaccount.ProvisionAccountService;
import com.usac.jobseeker.auth.application.usecase.provisionaccount.ProvisionAccountUseCase;
import com.usac.jobseeker.auth.application.usecase.resolveactor.ResolveActorService;
import com.usac.jobseeker.auth.application.usecase.resolveactor.ResolveActorUseCase;
import com.usac.jobseeker.auth.infrastructure.adapter.out.persistence.JdbcAccountRepository;
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
  ResolveActorUseCase resolveActorUseCase(JdbcAccountRepository repository) {
    return new ResolveActorService(repository);
  }
}
