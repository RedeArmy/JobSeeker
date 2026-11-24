package com.usac.jobseeker.app.infrastructure.security;

import com.usac.jobseeker.auth.application.usecase.resolveactor.ResolveActorUseCase;
import com.usac.jobseeker.common.application.account.AccountChangeObserver;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * Registra la resolución de actor con memoria por petición como la preferida (y el aviso que la
 * invalida): el `ActorFilter` y los adaptadores de los módulos de negocio piden
 * `ResolveActorUseCase` por tipo y reciben esta, de modo que comparten la primera consulta. La
 * original sigue disponible por su nombre.
 */
@Configuration(proxyBeanMethods = false)
class ActorResolutionConfiguration {

  /**
   * Las dos escrituras de `accounts` (aprovisionar y el estado de la identidad) avisan aquí, y el
   * ámbito de la petición olvida esa cuenta.
   */
  @Bean
  AccountChangeObserver actorResolutionInvalidation() {
    return ActorResolutionScope::forget;
  }

  @Bean
  @Primary
  ResolveActorUseCase requestScopedResolveActor(
      @Qualifier("resolveActorUseCase") ResolveActorUseCase original) {
    return new RequestScopedActorResolution(original);
  }
}
