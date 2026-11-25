package com.usac.jobseeker.app.infrastructure.security.operator;

import java.util.List;
import java.util.UUID;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Los operadores de la plataforma (`jobseeker.auth.operators.account-ids`): las cuentas que pueden
 * verificar a un reclutador (BR-ACCT-11). Vacía por defecto: sin configurarla nadie es operador y
 * el canal de verificación responde como si no existiera.
 *
 * @param accountIds identificadores de cuenta (el `sub` de Clavaris), separados por comas
 */
@ConfigurationProperties("jobseeker.auth.operators")
public record OperatorProperties(List<UUID> accountIds) {

  public OperatorProperties {
    accountIds = accountIds == null ? List.of() : List.copyOf(accountIds);
  }
}
