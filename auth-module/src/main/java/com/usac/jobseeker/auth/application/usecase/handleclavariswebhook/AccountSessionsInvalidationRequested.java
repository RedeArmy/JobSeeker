package com.usac.jobseeker.auth.application.usecase.handleclavariswebhook;

import com.usac.jobseeker.common.domain.model.AccountId;
import java.util.UUID;

/**
 * El evento de la bandeja que pide cerrar todas las sesiones de una cuenta. Las sesiones viven en
 * Redis, fuera de la base: se piden por la bandeja y no desde la transacción del webhook, así Redis
 * caído no pierde ni retrasa la aceptación del webhook (ADR-0016, decisión 11).
 *
 * <p>Solo lleva identificadores y el motivo: nunca datos personales (BR-DATA-01).
 */
public final class AccountSessionsInvalidationRequested {

  public static final String AGGREGATE_TYPE = "account";
  public static final String EVENT_TYPE = "account.sessions_invalidation_requested";

  private AccountSessionsInvalidationRequested() {}

  /**
   * El JSON de la carga. Se arma a mano porque todo lo que contiene son UUID y el tipo de evento de
   * Clavaris, que no llevan caracteres que escapar.
   */
  public static String payload(AccountId accountId, ClavarisEvent reason, UUID sourceEventId) {
    return "{\"accountId\":\""
        + accountId
        + "\",\"reason\":\""
        + reason.type()
        + "\",\"sourceEventId\":\""
        + sourceEventId
        + "\"}";
  }
}
