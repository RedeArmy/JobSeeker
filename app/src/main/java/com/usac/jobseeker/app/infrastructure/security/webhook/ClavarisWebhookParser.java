package com.usac.jobseeker.app.infrastructure.security.webhook;

import com.usac.jobseeker.auth.application.usecase.handleclavariswebhook.HandleClavarisWebhookCommand;
import com.usac.jobseeker.common.domain.model.AccountId;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reduce un webhook ya autenticado a identificadores (ADR-0016 decisión 11): el cuerpo de
 * `account.created`/`deleted` trae correo y el de `profile_updated`, nombre y teléfono, y nada de
 * eso debe salir de este método. Solo se lee `accountId` y `occurredAt`; el resto se descarta.
 */
final class ClavarisWebhookParser {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  private ClavarisWebhookParser() {}

  /**
   * El comando, o vacío si el cuerpo o las cabeceras no tienen la forma esperada. Cualquier dato
   * ausente o mal formado lanza y se trata igual: un solo camino, sin condiciones que puedan quedar
   * sin probar. Un tipo de evento en blanco no se rechaza aquí: el caso de uso lo ignora.
   */
  @SuppressWarnings(
      "PMD.AvoidCatchingGenericException") // todo dato mal formado es el mismo rechazo
  static Optional<HandleClavarisWebhookCommand> parse(
      byte[] body, String eventType, String eventId, String traceId) {
    try {
      JsonNode root = JSON.readTree(body);
      return Optional.of(
          new HandleClavarisWebhookCommand(
              UUID.fromString(eventId),
              eventType,
              new AccountId(accountId(root.path("accountId"))),
              occurredAt(root.path("occurredAt")),
              traceId == null || traceId.isBlank() ? null : traceId));
    } catch (RuntimeException e) {
      // JSON inválido, UUID mal formado, dato ausente, etc.: cuerpo sin la forma esperada.
      return Optional.empty();
    }
  }

  /** El identificador llega como `{"value":"…"}` (verificado en entregas reales) o como cadena. */
  private static UUID accountId(JsonNode node) {
    JsonNode value = node.isObject() ? node.path("value") : node;
    return UUID.fromString(value.asString());
  }

  /** ISO-8601 o, si Clavaris serializara fechas como número, segundos de época con fracción. */
  private static Instant occurredAt(JsonNode node) {
    if (node.isString()) {
      return Instant.parse(node.asString());
    }
    if (node.isNumber()) {
      BigDecimal seconds = node.decimalValue();
      long whole = seconds.longValue();
      long nanos = seconds.subtract(BigDecimal.valueOf(whole)).movePointRight(9).longValue();
      return Instant.ofEpochSecond(whole, nanos);
    }
    throw new IllegalArgumentException("occurredAt ausente o con un tipo inesperado");
  }
}
