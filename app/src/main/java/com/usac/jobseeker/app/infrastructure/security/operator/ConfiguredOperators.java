package com.usac.jobseeker.app.infrastructure.security.operator;

import com.usac.jobseeker.common.application.security.OperatorPolicyPort;
import com.usac.jobseeker.common.domain.model.AccountId;
import java.util.Set;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;

/**
 * La lista de operadores sale de la configuración y no de la base de datos ni de un claim del
 * token: ser operador es una decisión de quien despliega, no algo que una cuenta pueda concederse
 * (ADR-0020, las capacidades salen de la base y nunca de los claims). Cambiarla es un despliegue.
 */
@Component
public class ConfiguredOperators implements OperatorPolicyPort {

  /** Registra las propiedades junto a su único consumidor. */
  @Configuration(proxyBeanMethods = false)
  @EnableConfigurationProperties(OperatorProperties.class)
  static class Registration {}

  private final Set<AccountId> operators;

  public ConfiguredOperators(OperatorProperties properties) {
    this.operators =
        properties.accountIds().stream()
            .map(AccountId::new)
            .collect(java.util.stream.Collectors.toUnmodifiableSet());
  }

  @Override
  public boolean isOperator(AccountId accountId) {
    return operators.contains(accountId);
  }
}
