package com.usac.jobseeker.auth.application.usecase.handleclavariswebhook;

import com.usac.jobseeker.common.domain.model.AccountId;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Un webhook de Clavaris ya verificado y reducido a identificadores: la carga original trae correo,
 * nombre y teléfono, y se descarta antes de llegar aquí (ADR-0016, decisión 11).
 *
 * @param eventId `Clavaris-Event-Id`, con el que se deduplica
 * @param occurredAt cuándo ocurrió el evento en Clavaris, con el que se ordena
 * @param traceId correlación con el log; puede faltar
 */
public record HandleClavarisWebhookCommand(
    UUID eventId, String eventType, AccountId accountId, Instant occurredAt, String traceId) {

  public HandleClavarisWebhookCommand {
    Objects.requireNonNull(eventId, "eventId");
    Objects.requireNonNull(eventType, "eventType");
    Objects.requireNonNull(accountId, "accountId");
    Objects.requireNonNull(occurredAt, "occurredAt");
  }
}
